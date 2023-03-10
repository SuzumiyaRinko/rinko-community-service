package suzumiya.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HtmlUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.github.benmanes.caffeine.cache.Cache;
import lombok.extern.slf4j.Slf4j;
import org.elasticsearch.common.lucene.search.function.CombineFunction;
import org.elasticsearch.common.lucene.search.function.FieldValueFactorFunction;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.index.query.functionscore.FunctionScoreQueryBuilder;
import org.elasticsearch.index.query.functionscore.ScoreFunctionBuilders;
import org.elasticsearch.search.fetch.subphase.highlight.HighlightBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.elasticsearch.core.ElasticsearchRestTemplate;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.query.NativeSearchQuery;
import org.springframework.data.elasticsearch.core.query.NativeSearchQueryBuilder;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import suzumiya.constant.CacheConst;
import suzumiya.constant.CommonConst;
import suzumiya.constant.MQConstant;
import suzumiya.constant.RedisConst;
import suzumiya.mapper.CommentMapper;
import suzumiya.mapper.PostMapper;
import suzumiya.mapper.TagMapper;
import suzumiya.mapper.UserMapper;
import suzumiya.model.dto.*;
import suzumiya.model.pojo.Message;
import suzumiya.model.pojo.Post;
import suzumiya.model.pojo.User;
import suzumiya.model.vo.PostSearchVO;
import suzumiya.repository.PostRepository;
import suzumiya.service.ICacheService;
import suzumiya.service.IPostService;
import suzumiya.service.IUserService;
import suzumiya.util.IKAnalyzerUtils;
import suzumiya.util.RedisUtils;
import suzumiya.util.SuzumiyaUtils;

import javax.annotation.Resource;
import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class PostServiceImpl extends ServiceImpl<PostMapper, Post> implements IPostService {

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Resource
    private RabbitTemplate rabbitTemplate; // RabbitMQ

    @Resource(name = "postCache")
    private Cache<String, Object> postCache; // Caffeine

    @Resource
    private ElasticsearchRestTemplate esTemplate; // ES

    @Autowired
    private IUserService userService;

    @Autowired
    private ICacheService cacheService;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private TagMapper tagMapper;

    @Autowired
    private PostMapper postMapper;

    @Autowired
    private CommentMapper commentMapper;

    @Autowired
    private PostRepository postRepository;

    // ????????????
//    private final String[] aggsNames = {"tagAggregation"};
//    private final String[] aggsFieldNames = {"tagIDs"};
//    private final String[] aggsResultNames = {"??????"};

    @Override
    public Long insert(PostInsertDTO postInsertDTO) throws JsonProcessingException {
        /* ??????????????????????????? */
        String title = postInsertDTO.getTitle();
        if (title.length() < 5 || title.length() > 39 || postInsertDTO.getContent().length() > 2000) {
            throw new RuntimeException("????????????????????????????????????");
        }

        Post post = new Post();

        /* ??????????????? */
        post.setTitle(SuzumiyaUtils.replaceAllSensitiveWords(postInsertDTO.getTitle()));
        post.setContent(SuzumiyaUtils.replaceAllSensitiveWords(postInsertDTO.getContent()));

        /* ??????HTML?????? */
        post.setTitle(HtmlUtil.cleanHtmlTag(post.getTitle()));
        post.setContent(HtmlUtil.cleanHtmlTag(post.getContent()));

        /* ??????????????? */
        post.setTitle(post.getTitle().replaceAll(CommonConst.REPLACEMENT_ENTER, "<br>"));
        post.setContent(post.getContent().replaceAll(CommonConst.REPLACEMENT_ENTER, "<br>"));

        /* ??????post???MySQL */
        // ???tagIDs?????????tags
        List<Integer> tagIDs = postInsertDTO.getTagIDs();
        post.setTagIDs(tagIDs);
        int tags = 0;
        if (ObjectUtil.isNotEmpty(tagIDs)) {
            for (Integer tagID : tagIDs) {
                tags += Math.pow(2, tagID - 1);
            }
        }
        post.setTags(tags);
        // picturesSplit???pictures
        String[] picturesSpilt = postInsertDTO.getPicturesSplit();
        if (ObjectUtil.isNotEmpty(picturesSpilt)) {
            post.setPicturesSplit(picturesSpilt);
        } else {
            post.setPicturesSplit(new String[0]);
        }
        String pictures = StrUtil.join("|", picturesSpilt);
        post.setPictures(pictures);

        // ??????post???MySQL
        User user = (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        Long myId = user.getId();
        post.setUserId(myId);

        post.setCreateTime(LocalDateTime.now());
        postMapper.insert(post);

        /* ??????post???ES */
        postRepository.save(post);

        /* post +1 */
        redisTemplate.opsForValue().increment(RedisConst.POST_TOTAL_KEY);

        /* ??????????????????Post???Set?????? */
        redisTemplate.opsForSet().add(RedisConst.POST_SCORE_UPDATE_KEY, post.getId());

        /* ??????post?????????????????? */
        CacheClearDTO cacheClearDTO = new CacheClearDTO();
        cacheClearDTO.setKeyPattern(CacheConst.CACHE_POST_KEY_PATTERN);
        cacheClearDTO.setCaffeineType(CacheConst.CAFFEINE_TYPE_POST);
        rabbitTemplate.convertAndSend(MQConstant.SERVICE_DIRECT, MQConstant.CACHE_CLEAR_KEY, cacheClearDTO);

        /* ?????????????????????????????? */
        Set<Object> members = redisTemplate.opsForSet().members(RedisConst.USER_FOLLOWER_KEY + myId);
        if (ObjectUtil.isNotEmpty(members)) {
            List<Long> followerIds = members.stream().map((id) -> ((Integer) id).longValue()).collect(Collectors.toList());
            for (Long followerId : followerIds) {
                /* ??????follower???feed?????? */
                redisTemplate.opsForZSet().add(RedisConst.USER_FEED_KEY + followerId, post.getId(), RedisUtils.getZSetScoreBy2EpochSecond(LocalDateTime.now()));
                /* ?????????????????????????????? */
                MessageInsertDTO messageInsertDTO = new MessageInsertDTO();
                messageInsertDTO.setToUserId(followerId);
                messageInsertDTO.setEventUserId(myId);
                messageInsertDTO.setIsSystem(true);
                messageInsertDTO.setSystemMsgType(Message.SYSTEM_TYPE_POST_FOLLOWING);
                messageInsertDTO.setTargetId(post.getId());
                rabbitTemplate.convertAndSend(MQConstant.SERVICE_DIRECT, MQConstant.MESSAGE_INSERT_KEY, messageInsertDTO);
            }
        }

        return post.getId();
    }

    @Override
    public void delete(Long postId) {
        Post post2Delete = postMapper.selectOne(new LambdaQueryWrapper<Post>().eq(Post::getId, postId));
        if (post2Delete == null) {
            throw new RuntimeException("??????????????????");
        }

        /* ??????????????????????????? */
        User user = (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        Long myUserId = user.getId();
        if (!Objects.equals(post2Delete.getUserId(), myUserId)) {
            throw new RuntimeException("???POST????????????\n????????????");
        }

        /* ???MySQL???post???????????? */
        postMapper.deleteById(postId);

        /* ???ES???post?????? */
        postRepository.deleteById(postId);

        /* post -1 */
        redisTemplate.opsForValue().decrement(RedisConst.POST_TOTAL_KEY);

        /* ?????? */
        /* ??????post?????? */
        CacheClearDTO cacheClearDTO = new CacheClearDTO();
        cacheClearDTO.setKeyPattern(CacheConst.CACHE_POST_KEY_PATTERN);
        cacheClearDTO.setCaffeineType(CacheConst.CAFFEINE_TYPE_POST);
        rabbitTemplate.convertAndSend(MQConstant.SERVICE_DIRECT, MQConstant.CACHE_CLEAR_KEY, cacheClearDTO);

        /* ????????????comment */
        rabbitTemplate.convertAndSend(MQConstant.SERVICE_DIRECT, MQConstant.POST_DELETE_KEY, postId);
    }

//    public void update(PostUpdateDTO postUpdateDTO) {
//        /* ??????????????????????????? */
//        String title = postUpdateDTO.getTitle();
//        if (title.length() < 5 || title.length() > 40 || postUpdateDTO.getContent().length() > 5000) {
//            throw new RuntimeException("????????????????????????????????????");
//        }
//
//        /* ??????????????? */
//        Post post = new Post();
//        post.setTitle(WordTreeUtils.replaceAllSensitiveWords(postUpdateDTO.getTitle()));
//        post.setContent(WordTreeUtils.replaceAllSensitiveWords(postUpdateDTO.getContent()));
//
//        /* ??????HTML?????? */
////        post.setTitle(HtmlUtil.cleanHtmlTag(post.getTitle()));
////        post.setContent(HtmlUtil.cleanHtmlTag(post.getContent()));
//
//        /* ???MySQL?????????post */
//        post.setId(postUpdateDTO.getPostId());
//
//        List<Integer> tagIDs = postUpdateDTO.getTagIDs();
//        int tags = 0;
//        if (ObjectUtil.isNotEmpty(tagIDs)) {
//            for (Integer tagID : tagIDs) {
//                tags += Math.pow(2, tagID - 1);
//            }
//        }
//        post.setTags(tags);
//
//        post.setTitle(postUpdateDTO.getTitle());
//        post.setContent(postUpdateDTO.getContent());
//        post.setTagIDs(postUpdateDTO.getTagIDs());
//        int result = postMapper.updateById(post);
//        if (result == 0) {
//            throw new RuntimeException("??????????????????");
//        }
//
//        /* ???ES?????????post */
//        Optional<Post> optional = postRepository.findById(post.getId());
//        if (optional.isEmpty()) {
//            throw new RuntimeException("??????????????????");
//        }
//
//        Post t = optional.get();
//        t.setTitle(post.getTitle());
//        t.setContent(post.getContent());
//        t.setTagIDs(post.getTagIDs());
//        postRepository.save(t);
//
//        /* ??????post?????????????????? */
//        CacheClearDTO cacheClearDTO = new CacheClearDTO();
//        cacheClearDTO.setKeyPattern(CacheConst.CACHE_POST_KEY_PATTERN);
//        cacheClearDTO.setCaffeineType(CacheConst.CAFFEINE_TYPE_POST);
//        rabbitTemplate.convertAndSend(MQConstant.SERVICE_DIRECT, MQConstant.CACHE_CLEAR_KEY, cacheClearDTO);
//    }

    @Override
    public PostSearchVO search(PostSearchDTO postSearchDTO) throws NoSuchFieldException, IllegalAccessException {
        PostSearchVO postSearchVO = new PostSearchVO();

        String searchKey = postSearchDTO.getSearchKey();
        Long userId = postSearchDTO.getUserId();
        int sortType = postSearchDTO.getSortType();
        int pageNum = postSearchDTO.getPageNum();

        /* ??????isSearchMyself */
        User user = (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (postSearchDTO.getIsSearchMyself()) {
            postSearchDTO.setUserId(user.getId());
        }

        String cacheKey = null;
        boolean isCache = false;
        boolean flag = false;

        /* ??????isCache???cacheKey */
        // ??????????????????????????????????????????????????????????????????
        if (sortType == 1) {
            if (StrUtil.isBlank(searchKey) && userId != null && userId > 0 && user.getRoles().contains(1)) {
                // ????????????
                isCache = true;
                cacheKey = CacheConst.CACHE_POST_FAMOUS_KEY + userId + ":0:" + pageNum; // ?????????post???????????????????????????
            } else if (StrUtil.isBlank(searchKey) && (userId == null || userId < 0)) {
                isCache = true;
                cacheKey = CacheConst.CACHE_POST_NOT_FAMOUS_KEY + sortType + ":" + pageNum;
            }
        }

        /* ????????? */
        if (isCache) {
            // Caffeine
            Object t = postCache.getIfPresent(cacheKey);
            if (t != null) {
                Map<Object, Object> tt = (Map<Object, Object>) t;
                postSearchVO.setTotal((Integer) tt.get("total"));
                Collection<Post> collect = (Collection<Post>) ((Collection) tt.get("data")).stream().map((postMap) -> {
                    Post post = new Post();
                    BeanUtil.fillBeanWithMap((Map<?, ?>) postMap, post, null);
                    return post;
                }).collect(Collectors.toList());
                postSearchVO.setData(collect);
                flag = true;
            }

            // Redis
            Map<Object, Object> tt = redisTemplate.opsForHash().entries(cacheKey);
            if (ObjectUtil.isNotEmpty(tt)) {
                postSearchVO.setTotal((Integer) tt.get("total"));
                Collection<Post> collect = (Collection<Post>) ((Collection) tt.get("data")).stream().map((postMap) -> {
                    Post post = new Post();
                    BeanUtil.fillBeanWithMap((Map<?, ?>) postMap, post, null);
                    return post;
                }).collect(Collectors.toList());
                postSearchVO.setData(collect);
                flag = true;
            }
        }

        if (!isCache || !flag) {
            /* ??????ES */
            // ??????HotelSearchDTO??????SearchQuery??????
            NativeSearchQuery searchQuery = getSearchQuery(postSearchDTO);
            // ????????????
            SearchHits<Post> searchHits = esTemplate.search(searchQuery, Post.class);
            // ????????????
            postSearchVO = parseSearchHits(postSearchDTO, searchHits);
        }

        /* ???????????????????????????List<Post>?????????????????? */
        if (isCache && postSearchVO.getData().size() > 0) {
            /* ???????????????Caffeine???Redis?????????????????? */
            CacheUpdateDTO cacheUpdateDTO = new CacheUpdateDTO();
            cacheUpdateDTO.setCacheType(CacheConst.VALUE_TYPE_POJO);
            cacheUpdateDTO.setKey(cacheKey);
            cacheUpdateDTO.setValue(postSearchVO);
            cacheUpdateDTO.setCaffeineType(CacheConst.CAFFEINE_TYPE_POST);
            rabbitTemplate.convertAndSend(MQConstant.SERVICE_DIRECT, MQConstant.CACHE_UPDATE_KEY, cacheUpdateDTO);
        }

        List<Post> posts = (List<Post>) postSearchVO.getData();
        for (Post post : posts) {
            /* ????????????post??????SimpleUser */
            Long postUserId = post.getUserId();
            User simpleUser = userService.getSimpleUserById(postUserId);
            post.setPostUser(simpleUser);

            /* ????????????post??????likeCount, commentCount, collectionCount */
            Long postId = post.getId();
            ValueOperations<String, Object> valueOperations = redisTemplate.opsForValue();
            Object tmpLikeCount = valueOperations.get(RedisConst.POST_LIKE_COUNT_KEY + postId);
            Object tmpCommentCount = valueOperations.get(RedisConst.POST_COMMENT_COUNT_KEY + postId);
            Object tmpCollectionCount = valueOperations.get(RedisConst.POST_COLLECTION_COUNT_KEY + postId);
            int likeCount = 0;
            int commentCount = 0;
            int collectionCount = 0;
            if (tmpLikeCount != null) likeCount = (int) tmpLikeCount;
            if (tmpCommentCount != null) commentCount = (int) tmpCommentCount;
            if (tmpCollectionCount != null) collectionCount = (int) tmpCollectionCount;
            post.setLikeCount(likeCount);
            post.setCommentCount(commentCount);
            post.setCollectionCount(collectionCount);

            /* ??????first3picturesSplit */
            String[] picturesSplit = post.getPicturesSplit();
            int end = Math.min(picturesSplit.length, 3);
            String[] t = new String[end];
            for (int i = 0; i <= end - 1; i++) {
                t[i] = picturesSplit[i];
            }
            post.setFirst3PicturesSplit(t);
        }

        /* ???????????? */
        return postSearchVO;
    }

    @Override
    public Set<String> suggest(String searchKey) throws NoSuchFieldException, IllegalAccessException {
        if (StrUtil.isBlank(searchKey)) {
            throw new RuntimeException("?????????????????????");
        }

        PostSearchDTO postSearchDTO = new PostSearchDTO();
        postSearchDTO.setSearchKey(searchKey);
        postSearchDTO.setIsSuggestion(true);

        // ??????HotelSearchDTO??????SearchQuery??????
        NativeSearchQuery searchQuery = getSearchQuery(postSearchDTO);
        // ????????????
        SearchHits<Post> searchHits = esTemplate.search(searchQuery, Post.class);
        // ????????????
        return (Set<String>) (parseSearchHits(postSearchDTO, searchHits).getData());
    }

    /* ??????SearchQuery?????? */
    private NativeSearchQuery getSearchQuery(PostSearchDTO postSearchDTO) {
        /* ??????Builder?????? */
        NativeSearchQueryBuilder builder = new NativeSearchQueryBuilder();

        /* ???????????? */
        if (Boolean.TRUE.equals(postSearchDTO.getIsSuggestion())) {
            builder.withQuery(QueryBuilders.matchQuery("searchField", postSearchDTO.getSearchKey()));
            builder.withPageable(PageRequest.of(0, CommonConst.STANDARD_PAGE_SIZE)); // ES???????????????"0"????????????
            return builder.build();
        }

        /* ??????bool?????? */
        BoolQueryBuilder boolQuery = QueryBuilders.boolQuery();
        // searchKey
        String searchKey = postSearchDTO.getSearchKey();
        if (StrUtil.isBlank(searchKey)) {
            boolQuery.must(QueryBuilders.matchAllQuery());
        } else {
            boolQuery.must(QueryBuilders.matchQuery("searchField", searchKey));
        }
        // ??????
        int[] tagIDs = postSearchDTO.getTagIDs();
        if (ArrayUtil.isNotEmpty(tagIDs)) {
            for (Integer tagID : tagIDs) {
                boolQuery.filter(QueryBuilders.matchQuery("tagIDs", tagID));
            }
        }
        // ??????userId??????post
        Long userId = postSearchDTO.getUserId();
        if (userId != null) {
            boolQuery.filter(QueryBuilders.matchQuery("userId", userId));
        }

        /* ?????????????????? */
        FunctionScoreQueryBuilder functionScoreQuery = QueryBuilders.functionScoreQuery(
                boolQuery,
                new FunctionScoreQueryBuilder.FilterFunctionBuilder[]{
                        new FunctionScoreQueryBuilder.FilterFunctionBuilder(QueryBuilders.termQuery("isTop", true), ScoreFunctionBuilders.weightFactorFunction(100)),
                        new FunctionScoreQueryBuilder.FilterFunctionBuilder(ScoreFunctionBuilders.fieldValueFactorFunction("score").missing(0.0).modifier(FieldValueFactorFunction.Modifier.LOG2P))
                });
        functionScoreQuery.boostMode(CombineFunction.SUM);
        builder.withQuery(functionScoreQuery);

        /* ???????????? */
        int sortType = postSearchDTO.getSortType();
        if (sortType == PostSearchDTO.SORT_TYPE_SCORE) {
            builder.withSort(Sort.by(Sort.Order.desc("score"))); // ??????post??????????????????
        } else if (sortType == PostSearchDTO.SORT_TYPE_TIME) {
            builder.withSort(Sort.by(Sort.Order.desc("createTime"))); // ??????post????????????????????????
        }

        /* ???????????? */
        int pageNum = postSearchDTO.getPageNum();
        if (pageNum <= 0) {
            pageNum = 1;
        }
        builder.withPageable(PageRequest.of(pageNum - 1, CommonConst.STANDARD_PAGE_SIZE)); // ES???????????????"0"????????????

        /* ???????????? */
        builder.withHighlightFields(
                new HighlightBuilder.Field("title").requireFieldMatch(false).preTags("<em>").postTags("</em>"),
                new HighlightBuilder.Field("content").requireFieldMatch(false).preTags("<em>").postTags("</em>")
        );

        /* ???????????? */
//        int len = aggsNames.length;
//        for (int i = 0; i <= len - 1; i++) {
//            builder.withAggregations(AggregationBuilders.terms(aggsNames[i]).field(aggsFieldNames[i]).size(31).order(BucketOrder.aggregation("_count", false)));
//        }

        /* ???????????????SearchQuery?????? */
        return builder.build();
    }

    /* ???????????? */
    private PostSearchVO parseSearchHits(PostSearchDTO postSearchDTO, SearchHits<Post> searchHits) throws NoSuchFieldException, IllegalAccessException {
        PostSearchVO postSearchVO = new PostSearchVO();

        /* ???????????? */
        if (Boolean.TRUE.equals(postSearchDTO.getIsSuggestion())) {
            List<String> parseList = IKAnalyzerUtils.parse(postSearchDTO.getSearchKey());
            List<SearchHit<Post>> hits = searchHits.getSearchHits();
            Set<String> suggestions = new HashSet<>();
            for (SearchHit<Post> hit : hits) {
                // 1 ?????? _source
                Post post = hit.getContent();
                // 2 ????????????
                String title = post.getTitle();
                for (String word : parseList) {
                    title = StrUtil.replace(title, word, "<em>" + word + "</em>", true);
                }
                // 3 ?????????suggestions
                suggestions.add(title);
            }
            postSearchVO.setData(suggestions);
            return postSearchVO;
        }

        /* ?????????????????? */
        int total = (int) searchHits.getTotalHits();
        List<SearchHit<Post>> hits = searchHits.getSearchHits();
        List<Post> postsResult = new ArrayList<>();
        for (SearchHit<Post> hit : hits) {
            // 1 ?????? _source
            Post post = hit.getContent();
            // 2 tagIDs???tagsStr
            List<Integer> tagIDs = post.getTagIDs();
            if (ObjectUtil.isNotEmpty(tagIDs)) {
                List<String> tagsStr = tagMapper.getAllNameByTagIDs(tagIDs);
                post.setTagsStr(tagsStr);
            }
            // 3 ?????? highlight
            Map<String, List<String>> highlightFields = hit.getHighlightFields();
            if (!CollectionUtil.isEmpty(highlightFields)) {
                Set<String> keySet = highlightFields.keySet();
                // ????????????key???????????????value????????????????????????post????????????
                for (String s : keySet) {
                    List<String> highlightField = highlightFields.get(s);
                    if (highlightField != null && !CollectionUtil.isEmpty(highlightField)) {
                        String highlightStr = highlightField.get(0);
                        Field field = Post.class.getDeclaredField(s);
                        field.setAccessible(true);
                        field.set(post, highlightStr);
                    }
                }
            }
            // 4 ????????????
            postsResult.add(post);
        }

        postSearchVO.setTotal(total);
        postSearchVO.setData(postsResult);
        return postSearchVO;
    }

    @Override
    public void like(Long postId) {
        User user = (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        Long myId = user.getId();

        if (Boolean.TRUE.equals(redisTemplate.opsForSet().isMember(RedisConst.POST_LIKE_LIST_KEY + postId, myId))) {
            /* ????????????post???????????? */
            redisTemplate.opsForValue().decrement(RedisConst.POST_LIKE_COUNT_KEY + postId);
            /* ??????post???like???????????????user */
            redisTemplate.opsForSet().remove(RedisConst.POST_LIKE_LIST_KEY + postId, myId);
        } else {
            /* ????????????post???????????? */
            redisTemplate.opsForValue().increment(RedisConst.POST_LIKE_COUNT_KEY + postId);
            /* ??????post???like???????????????user */
            redisTemplate.opsForSet().add(RedisConst.POST_LIKE_LIST_KEY + postId, myId);
            /* ?????????????????????????????? */
            Long toUserId = postMapper.getUserIdByPostId(postId);
            // ??????????????????????????????
            if (!ObjectUtil.equals(myId, toUserId)) {
                MessageInsertDTO messageInsertDTO = new MessageInsertDTO();
                messageInsertDTO.setToUserId(toUserId);
                messageInsertDTO.setEventUserId(myId);
                messageInsertDTO.setIsSystem(true);
                messageInsertDTO.setSystemMsgType(Message.SYSTEM_TYPE_POST_LIKE);
                messageInsertDTO.setTargetId(postId);
                rabbitTemplate.convertAndSend(MQConstant.SERVICE_DIRECT, MQConstant.MESSAGE_INSERT_KEY, messageInsertDTO);
            }
        }
    }

    @Override
    public void collect(Long postId) {
        //TODO ???2??????????????????????????????
        User user = (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        Long myId = user.getId();
//        Long myId = 1L; // ??????????????????????????????

        if (redisTemplate.opsForZSet().score(RedisConst.USER_COLLECTIONS_KEY + myId, postId) != null) {
            /* ????????????post???????????? */
            redisTemplate.opsForValue().decrement(RedisConst.POST_COLLECTION_COUNT_KEY + postId);
            /* ??????post???collection???????????????user */
            redisTemplate.opsForSet().remove(RedisConst.POST_COLLECTION_LIST_KEY + postId, myId);
            /* ??????user???collection???????????????post */
            redisTemplate.opsForZSet().remove(RedisConst.USER_COLLECTIONS_KEY + myId, postId);
        } else {
            /* ????????????post???????????? */
            redisTemplate.opsForValue().increment(RedisConst.POST_COLLECTION_COUNT_KEY + postId);
            /* ??????post???collection???????????????user */
            redisTemplate.opsForSet().add(RedisConst.POST_COLLECTION_LIST_KEY + postId, myId);
            /* ??????user???collection???????????????post */
            redisTemplate.opsForZSet().add(RedisConst.USER_COLLECTIONS_KEY + myId, postId, RedisUtils.getZSetScoreBy2EpochSecond(LocalDateTime.now()));
            /* ?????????????????????????????? */
            Long toUserId = postMapper.getUserIdByPostId(postId);
            if (!ObjectUtil.equals(myId, toUserId)) {
                MessageInsertDTO messageInsertDTO = new MessageInsertDTO();
                messageInsertDTO.setMyId(myId);
                messageInsertDTO.setToUserId(toUserId);
                messageInsertDTO.setEventUserId(myId);
                messageInsertDTO.setIsSystem(true);
                messageInsertDTO.setSystemMsgType(Message.SYSTEM_TYPE_POST_COLLECT);
                messageInsertDTO.setTargetId(postId);
                rabbitTemplate.convertAndSend(MQConstant.SERVICE_DIRECT, MQConstant.MESSAGE_INSERT_KEY, messageInsertDTO);
            }
        }
    }

    @Override
    public Boolean hasLike(Long postId) {
        User user = (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        Long myUserId = user.getId();
        return redisTemplate.opsForSet().isMember(RedisConst.POST_LIKE_LIST_KEY + postId, myUserId);
    }

    @Override
    public Boolean hasCollect(Long postId) {
        User user = (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        Long myUserId = user.getId();
        Double score = redisTemplate.opsForZSet().score(RedisConst.USER_COLLECTIONS_KEY + myUserId, postId);
        return score != null;
    }

    @Override
    public PostSearchVO getCollections(Integer pageNum) {
        /* ?????????????????????collectionPostIDs */
        User user = (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        Long myUserId = user.getId();

        return getFromESByZS(RedisConst.USER_COLLECTIONS_KEY + myUserId, pageNum);
    }

    @Override
    public PostSearchVO getFeeds(Integer pageNum) {
        /* ?????????????????????collectionPostIDs */
        User user = (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        Long myUserId = user.getId();

        return getFromESByZS(RedisConst.USER_FEED_KEY + myUserId, pageNum);
    }

    @Override
    public Post getPostByCommentId(Long commentId) {
        Long postId = commentMapper.getTargetIdByCommentId(commentId);
        Optional<Post> postOptional = postRepository.findById(postId);
        if (postOptional.isEmpty()) {
            throw new RuntimeException("???post?????????");
        }

        Post post = postOptional.get();

        /* ????????????post??????SimpleUser */
        Long postUserId = post.getUserId();
        User simpleUser = userService.getSimpleUserById(postUserId);
        post.setPostUser(simpleUser);

        /* ????????????post??????likeCount, commentCount, collectionCount */
        ValueOperations<String, Object> valueOperations = redisTemplate.opsForValue();
        Object tmpLikeCount = valueOperations.get(RedisConst.POST_LIKE_COUNT_KEY + postId);
        Object tmpCommentCount = valueOperations.get(RedisConst.POST_COMMENT_COUNT_KEY + postId);
        Object tmpCollectionCount = valueOperations.get(RedisConst.POST_COLLECTION_COUNT_KEY + postId);
        int likeCount = 0;
        int commentCount = 0;
        int collectionCount = 0;
        if (tmpLikeCount != null) likeCount = (int) tmpLikeCount;
        if (tmpCommentCount != null) commentCount = (int) tmpCommentCount;
        if (tmpCollectionCount != null) collectionCount = (int) tmpCollectionCount;
        post.setLikeCount(likeCount);
        post.setCommentCount(commentCount);
        post.setCollectionCount(collectionCount);

        return post;
    }

    @Override
    public Post getPostByPostId(Long postId) {
        Optional<Post> postOptional = postRepository.findById(postId);
        if (postOptional.isEmpty()) {
            throw new RuntimeException("???POST?????????\n??????????????????");
        }

        Post post = postOptional.get();

        /* ????????????post??????SimpleUser */
        post.setPostUser(userService.getSimpleUserById(post.getUserId()));

        /* ????????????post??????likeCount, commentCount, collectionCount */
        ValueOperations<String, Object> valueOperations = redisTemplate.opsForValue();
        Object tmpLikeCount = valueOperations.get(RedisConst.POST_LIKE_COUNT_KEY + postId);
        Object tmpCommentCount = valueOperations.get(RedisConst.POST_COMMENT_COUNT_KEY + postId);
        Object tmpCollectionCount = valueOperations.get(RedisConst.POST_COLLECTION_COUNT_KEY + postId);
        int likeCount = 0;
        int commentCount = 0;
        int collectionCount = 0;
        if (tmpLikeCount != null) likeCount = (int) tmpLikeCount;
        if (tmpCommentCount != null) commentCount = (int) tmpCommentCount;
        if (tmpCollectionCount != null) collectionCount = (int) tmpCollectionCount;
        post.setLikeCount(likeCount);
        post.setCommentCount(commentCount);
        post.setCollectionCount(collectionCount);

        return post;
    }

    @Override
    public void setWonderful(List<Long> postIds) {
        /* ?????? */
        if (ObjectUtil.isEmpty(postIds)) {
            return;
        }

        /* ???MySQL?????????post */
        List<Post> posts = postIds.stream().map((postId) -> {
            Post t = new Post();
            t.setId(postId);
            t.setIsWonderful(true);
            return t;
        }).collect(Collectors.toList());
        this.updateBatchById(posts);

        /* ???ES?????????post */
        posts = posts.stream().map((post) -> {
            Optional<Post> optional = postRepository.findById(post.getId());
            if (optional.isEmpty()) {
                throw new RuntimeException("??????????????????");
            }
            post = optional.get();
            post.setIsWonderful(true);
            return post;
        }).collect(Collectors.toList());
        postRepository.saveAll(posts);

        /* ??????????????????Post???Set?????? */
        redisTemplate.opsForSet().add(RedisConst.POST_SCORE_UPDATE_KEY, postIds.toArray());

        /* ??????post?????? */
        CacheClearDTO cacheClearDTO = new CacheClearDTO();
        cacheClearDTO.setKeyPattern(CacheConst.CACHE_POST_KEY_PATTERN);
        cacheClearDTO.setCaffeineType(CacheConst.CAFFEINE_TYPE_POST);
        cacheService.clearCache(cacheClearDTO);
    }

    private PostSearchVO getFromESByZS(String zsetKey, Integer pageNum) {
        PostSearchVO postSearchVO = new PostSearchVO();

        long startIndex = (long) (pageNum - 1) * CommonConst.STANDARD_PAGE_SIZE;
        Set<Object> t = redisTemplate.opsForZSet().reverseRange(zsetKey, startIndex, CommonConst.STANDARD_PAGE_SIZE);

        if (ObjectUtil.isEmpty(t)) {
            postSearchVO.setTotal(0);
            postSearchVO.setData(new ArrayList<>());
        } else {
            List<Long> feedPostIDs = t.stream().map((el) -> (long) (Integer) el).collect(Collectors.toList());
            Iterable<Post> collectionPost = postRepository.findAllById(feedPostIDs);
            for (Post post : collectionPost) {
                /* ????????????post??????SimpleUser */
                Long postUserId = post.getUserId();
                User simpleUser = userService.getSimpleUserById(postUserId);
                post.setPostUser(simpleUser);

                /* ????????????post??????likeCount, commentCount, collectionCount */
                Long postId = post.getId();
                ValueOperations<String, Object> valueOperations = redisTemplate.opsForValue();
                Object tmpLikeCount = valueOperations.get(RedisConst.POST_LIKE_COUNT_KEY + postId);
                Object tmpCommentCount = valueOperations.get(RedisConst.POST_COMMENT_COUNT_KEY + postId);
                Object tmpCollectionCount = valueOperations.get(RedisConst.POST_COLLECTION_COUNT_KEY + postId);
                int likeCount = 0;
                int commentCount = 0;
                int collectionCount = 0;
                if (tmpLikeCount != null) likeCount = (int) tmpLikeCount;
                if (tmpCommentCount != null) commentCount = (int) tmpCommentCount;
                if (tmpCollectionCount != null) collectionCount = (int) tmpCollectionCount;
                post.setLikeCount(likeCount);
                post.setCommentCount(commentCount);
                post.setCollectionCount(collectionCount);

                /* ??????first3picturesSplit */
                String[] picturesSplit = post.getPicturesSplit();
                int end = Math.min(picturesSplit.length, 3);
                String[] tt = new String[end];
                for (int i = 0; i <= end - 1; i++) {
                    tt[i] = picturesSplit[i];
                }
                post.setFirst3PicturesSplit(tt);

                /* ?????????????????????postId */
                t.remove(post.getId().intValue());
            }
            Long total = redisTemplate.opsForZSet().zCard(zsetKey);
            postSearchVO.setTotal(total.intValue());
            postSearchVO.setData((List<Post>) collectionPost);

            /* ???????????? collectionsZset/feedsZset ????????????????????????????????????POST */
            for (Object tt : t) {
                Integer deletedPostId = (Integer) tt;
                redisTemplate.opsForZSet().remove(zsetKey, deletedPostId);
            }
        }

        return postSearchVO;
    }
}
