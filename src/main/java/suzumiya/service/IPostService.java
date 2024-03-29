package suzumiya.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.fasterxml.jackson.core.JsonProcessingException;
import suzumiya.model.dto.PostInsertDTO;
import suzumiya.model.dto.PostSearchDTO;
import suzumiya.model.dto.PostUpdateDTO;
import suzumiya.model.pojo.Post;
import suzumiya.model.vo.PostSearchVO;

import java.util.List;
import java.util.Set;

public interface IPostService extends IService<Post> {

    /* 新增 */
    Long insert(PostInsertDTO postInsertDTO) throws JsonProcessingException;

    /* 删除 */
    void delete(Long postId);

    /* 更新 */
    void update(PostUpdateDTO postUpdateDTO) throws JsonProcessingException;

    /* 查询 */
    PostSearchVO search(PostSearchDTO postSearchDTO) throws NoSuchFieldException, IllegalAccessException;

    /* 搜索联想 */
    Set<String> suggest(String searchKey) throws NoSuchFieldException, IllegalAccessException;

    /* 加精 */
    void setWonderful(List<Long> postIds);

    /* 点赞或取消点赞 */
    void like(Long postId);

    /* 收藏或取消收藏 */
    void collect(Long postId);

    /* 判断是否已点赞 */
    Boolean hasLike(Long postId);

    /* 判断是否已收藏 */
    Boolean hasCollect(Long postId);

    /* 查询某个用户的CollectionPost */
    PostSearchVO getCollections(Integer pageNum);

    /* 查询当前用户的Feed集合 */
    PostSearchVO getFeeds(Integer pageNum);

    /* 根据postId获取post */
    Post getPostByPostId(Long postId);

    /* 根据commentId获取post */
    Post getPostByCommentId(Long commentId);
}
