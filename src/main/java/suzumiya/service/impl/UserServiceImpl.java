package suzumiya.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.jwt.JWTUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.github.benmanes.caffeine.cache.Cache;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import suzumiya.constant.CacheConst;
import suzumiya.constant.CommonConst;
import suzumiya.constant.MQConstant;
import suzumiya.constant.RedisConst;
import suzumiya.mapper.UserMapper;
import suzumiya.model.dto.*;
import suzumiya.model.pojo.Message;
import suzumiya.model.pojo.User;
import suzumiya.model.vo.FollowingSelectVO;
import suzumiya.model.vo.UserInfoVo;
import suzumiya.service.IFileService;
import suzumiya.service.IUserService;
import suzumiya.util.SuzumiyaUtils;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService, UserDetailsService {

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Resource
    private RabbitTemplate rabbitTemplate;

    @Resource(name = "userCache")
    private Cache<String, Object> userCache; // Caffeine

    @Autowired
    @Lazy
    private AuthenticationManager authenticationManager;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private IFileService fileService;

    @Autowired
    private UserMapper userMapper;

    private static final String TOKEN_KEY = "114514"; // Token??????

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        /* ?????????????????? */
        return userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, username));
    }

    @Override
    public String login(UserLoginDTO userLoginDTO) {
        /* ????????????????????????????????????????????? */
        String username = userLoginDTO.getUsername();
        String password = userLoginDTO.getPassword();
        if (username != null && !(username.matches(CommonConst.REGEX_EMAIL) && password.matches(CommonConst.REGEX_PASSWORD))) {
            throw new RuntimeException("??????????????????????????????");
        }

        /* ??????Authentication????????????SS????????? */
        // ?????????????????????salt
        // username???null???????????????
        User existedUser = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, userLoginDTO.getUsername()));
        // ?????????????????????
        if (existedUser == null) {
            throw new RuntimeException("??????????????????");
        }
        // ???????????????????????????
        if (existedUser.getActivation() == 0) {
            throw new RuntimeException("??????????????????");
        }
        userLoginDTO.setSalt(existedUser.getSalt());
        // ??????????????????
        Authentication authenticationToken = new UsernamePasswordAuthenticationToken(userLoginDTO.getUsername(), userLoginDTO.getPassword() + userLoginDTO.getSalt());
        Authentication authentication;
        try {
            // SS??????UserDetail
            // ???????????????UserDetail.getAuthorities()????????????????????????authoritiesStr?????????????????????UserDetail.getAuthorities()??????null
            authentication = authenticationManager.authenticate(authenticationToken); // ?????????loadUserByUsername
        } catch (Exception ex) {
            ex.printStackTrace();
            throw new RuntimeException("?????????????????????");
        }


        /* ?????????????????? */
        User authenticatedUser = (User) authentication.getPrincipal();
        List<String> authoritiesStr = userMapper.getAuthoritiesStrByUserId(authenticatedUser.getId());
        authenticatedUser.setAuthoritiesStr(authoritiesStr);

        /* ??????roles */
        authenticatedUser.setRoles(userMapper.getRolesByUserId(authenticatedUser.getId()));

        /* ????????????????????????Redis??????TTL???30mins */
        String key = RedisConst.LOGIN_USER_KEY + authenticatedUser.getId();
        Map<String, Object> value = new HashMap<>();
        BeanUtil.beanToMap(authenticatedUser, value, true, null);
        redisTemplate.opsForHash().putAll(key, value);
        redisTemplate.expire(key, 30, TimeUnit.MINUTES);

        /* ???????????????Jwt */
        HashMap<String, Object> payload = new HashMap<>();
        payload.put("userId", authenticatedUser.getId());
//        return "Bearer " + JWTUtil.createToken(payload, TOKEN_KEY.getBytes(StandardCharsets.UTF_8));
        return JWTUtil.createToken(payload, TOKEN_KEY.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String loginAnonymously() {
        /* ???????????? */
        String nickname = "user_" + UUID.randomUUID().toString().substring(0, 6); // [0, 6)

        User newUser = new User();
        newUser.setNickname(nickname);
        newUser.setActivation(2);
        newUser.setAvatar(CommonConst.DEFAULT_AVATAR);
        userMapper.insert(newUser);

        /* ????????????????????????Redis??????TTL???30mins */
        String key = RedisConst.LOGIN_USER_KEY + newUser.getId();
        Map<String, Object> value = new HashMap<>();
        BeanUtil.beanToMap(newUser, value, true, null);
        redisTemplate.opsForHash().putAll(key, value);
        redisTemplate.expire(key, 30, TimeUnit.MINUTES);

        /* ???????????????Jwt */
        HashMap<String, Object> payload = new HashMap<>();
        payload.put("userId", newUser.getId());
        return JWTUtil.createToken(payload, TOKEN_KEY.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void logout() {
        /* ??????Redis?????????????????? */
        User user = (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        redisTemplate.delete(RedisConst.LOGIN_USER_KEY + user.getId());
    }

    @Override
    public void register(UserRegisterDTO userRegisterDTO) {
        /* ??????????????????????????? */
        String uuid = userRegisterDTO.getUuid();
        String correctCode;
        Object o = redisTemplate.opsForValue().get(RedisConst.USER_VERIFY_KEY + uuid);
        if (o != null) {
            correctCode = ((String) o).toLowerCase();
        } else {
            throw new RuntimeException("????????????");
        }

        String code = userRegisterDTO.getCode().toLowerCase();
        if (!StrUtil.equals(correctCode, code)) {
            throw new RuntimeException("????????????");
        }

        /* ????????????????????????????????????????????? */
        String username = userRegisterDTO.getUsername();
        String password = userRegisterDTO.getPassword();
        if (username != null && !(username.matches(CommonConst.REGEX_EMAIL) && password.matches(CommonConst.REGEX_PASSWORD))) {
            throw new RuntimeException("??????????????????????????????");
        }

        /* ??????password???confirmPassword???????????? */
        String confirmPassword = userRegisterDTO.getConfirmPassword();
        if (!StrUtil.equals(password, confirmPassword)) {
            throw new RuntimeException("????????????");
        }

        /* ??????????????????????????????????????? */
        User existedUser = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, userRegisterDTO.getUsername()));
        if (existedUser != null) {
            throw new RuntimeException("????????????????????????");
        }

        /* ???????????? */
        String salt = UUID.randomUUID().toString().substring(0, 8); // [0, 8)
        String encodePassword = passwordEncoder.encode(userRegisterDTO.getPassword() + salt);

        User newUser = new User();
        newUser.setUsername(userRegisterDTO.getUsername());
        newUser.setPassword(encodePassword);
        newUser.setSalt(salt);

        uuid = UUID.randomUUID().toString();
        newUser.setActivationUUID(uuid);
        newUser.setNickname("user_" + uuid.substring(0, 8));
        newUser.setAvatar(CommonConst.DEFAULT_AVATAR);
        userMapper.insert(newUser);

        /* 30mins???????????????????????? */
        /* ??????????????????????????????????????? */
        rabbitTemplate.convertAndSend(MQConstant.SERVICE_DIRECT, MQConstant.USER_REGISTER_KEY, newUser);
    }

    @Override
    public void activate(String uuid, HttpServletResponse response) throws IOException {
        /* ?????????????????????????????? */
        response.setStatus(200);
        response.setContentType("text/html;charset=utf-8");
        response.setCharacterEncoding("utf-8");

        /* ??????????????????????????? */
        User existedUser = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getActivationUUID, uuid));
        if (existedUser == null) {
            throw new RuntimeException("??????????????????");
        }
        /* ?????????????????????????????? */
        if (existedUser.getActivation() == 1) {
            response.getWriter().print(CommonConst.HTML_ACTIVATION_SUCCESS
                    .replaceAll("<xxxxx>", existedUser.getUsername())
                    .replaceAll("<yyyyy>", CommonConst.USER_LOGIN_URL));
            return; // ???????????????
        }
        /* ????????????????????????????????? */
        Object t = redisTemplate.opsForValue().get(RedisConst.ACTIVATION_USER_KEY + uuid);
        if (t == null) {
            userMapper.deleteById(existedUser.getId());
            response.getWriter().print(CommonConst.HTML_ACTIVATION_EXPIRED
                    .replaceAll("<yyyyy>", CommonConst.USER_REGISTER_URL));
            return; // ??????????????????
        }

        Integer userId = (Integer) t; // Redis?????????"Long"??????????????????

        /* ???????????? */
        User tt = new User();
        tt.setId((long) userId);
        tt.setActivation(1);
        int result = userMapper.updateById(tt);
        if (result == 1) {
            redisTemplate.delete(RedisConst.ACTIVATION_USER_KEY + uuid);
            response.getWriter().print(CommonConst.HTML_ACTIVATION_SUCCESS
                    .replaceAll("<xxxxx>", existedUser.getUsername())
                    .replaceAll("<yyyyy>", CommonConst.USER_LOGIN_URL));
        }
    }

    @Override
    public User getSimpleUserById(Long userId) {
        String cacheKey = CacheConst.CACHE_USER_KEY + userId;
        User user = new User();

        /* ???????????? */
        boolean flag = false;
        // Caffeine
        Object t = userCache.getIfPresent(cacheKey);
        if (t != null) {
            BeanUtil.fillBeanWithMap((Map<?, ?>) t, user, null);
            flag = true;
        }

        // Redis
        if (!flag) {
            Map<Object, Object> entries = redisTemplate.opsForHash().entries(cacheKey);
            if (ObjectUtil.isNotEmpty(entries)) {
                BeanUtil.fillBeanWithMap(entries, user, null);
                flag = true;
            }
        }

        // MySQL
        if (!flag) {
            user = userMapper.getSimpleUserById(userId);
            user.setRoles(userMapper.getRolesByUserId(userId));
        }

        /* ???????????????Caffeine???Redis?????????????????? */
        CacheUpdateDTO cacheUpdateDTO = new CacheUpdateDTO();
        cacheUpdateDTO.setCacheType(CacheConst.VALUE_TYPE_POJO);
        cacheUpdateDTO.setKey(cacheKey);
        cacheUpdateDTO.setValue(user);
        cacheUpdateDTO.setCaffeineType(CacheConst.CAFFEINE_TYPE_USER);
        rabbitTemplate.convertAndSend(MQConstant.SERVICE_DIRECT, MQConstant.CACHE_UPDATE_KEY, cacheUpdateDTO);

        return user;
    }

    @Override
    public void follow(Long targetId) {
        User user = (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        Long myUserId = user.getId();

        /* ?????????????????? */
        if (targetId.equals(myUserId)) {
            return;
        }

        if (Boolean.TRUE.equals(redisTemplate.opsForSet().isMember(RedisConst.USER_FOLLOWING_KEY + myUserId, targetId))) {
            /* ????????????target */
            redisTemplate.opsForSet().remove(RedisConst.USER_FOLLOWING_KEY + myUserId, targetId);
            redisTemplate.opsForSet().remove(RedisConst.USER_FOLLOWER_KEY + targetId, myUserId);
            /* ????????????Feed??????????????????????????????????????? */
            UserUnfollowDTO userUnfollowDTO = new UserUnfollowDTO();
            userUnfollowDTO.setMyUserId(myUserId);
            userUnfollowDTO.setTargetId(targetId);
            rabbitTemplate.convertAndSend(MQConstant.SERVICE_DIRECT, MQConstant.USER_UNFOLLOW_KEY, userUnfollowDTO);
        } else {
            /* ??????target */
            redisTemplate.opsForSet().add(RedisConst.USER_FOLLOWING_KEY + myUserId, targetId);
            redisTemplate.opsForSet().add(RedisConst.USER_FOLLOWER_KEY + targetId, myUserId);
            /* ?????????????????????????????? */
            MessageInsertDTO messageInsertDTO = new MessageInsertDTO();
            messageInsertDTO.setToUserId(targetId);
            messageInsertDTO.setEventUserId(myUserId);
            messageInsertDTO.setIsSystem(true);
            messageInsertDTO.setSystemMsgType(Message.SYSTEM_TYPE_SOMEONE_FOLLOWING);
            messageInsertDTO.setTargetId(myUserId);
            rabbitTemplate.convertAndSend(MQConstant.SERVICE_DIRECT, MQConstant.MESSAGE_INSERT_KEY, messageInsertDTO);
        }
    }

    @Override
    public Boolean hasFollow(Long targetId) {
        User user = (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        Long myUserId = user.getId();
        return redisTemplate.opsForSet().isMember(RedisConst.USER_FOLLOWING_KEY + myUserId, targetId);
    }

    @Override
    public FollowingSelectVO getFollowings(Long lastId) {
        User user = (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        Long myId = user.getId();

        /* ?????????????????????followingIds */
        List<User> followings = new ArrayList<>();
        List<Long> followingIds;
        if (lastId == null) {
            Set<Object> range = redisTemplate.opsForZSet().range(RedisConst.USER_FOLLOWING_KEY + myId, 0, IUserService.FOLLOWINGS_STANDARD_PAGE_SIZE - 1);
            followingIds = range.stream().map((id) -> ((Integer) id).longValue()).collect(Collectors.toList());
        } else {
            Long lastIdRank = redisTemplate.opsForZSet().rank(RedisConst.USER_FOLLOWING_KEY + myId, lastId);
            Set<Object> range = redisTemplate.opsForZSet().range(RedisConst.USER_FOLLOWING_KEY + myId, lastIdRank + 1, lastIdRank + IUserService.FOLLOWINGS_STANDARD_PAGE_SIZE);
            followingIds = range.stream().map((id) -> ((Integer) id).longValue()).collect(Collectors.toList());
        }

        /* ?????? */
        if (ObjectUtil.isNotEmpty(followingIds)) {
            for (Long followingId : followingIds) {
                User simpleUserById = this.getSimpleUserById(followingId);
                followings.add(simpleUserById);
            }
        }

        lastId = followings.get(followings.size() - 1).getId();

        FollowingSelectVO followingSelectVO = new FollowingSelectVO();
        followingSelectVO.setFollowings(followings);
        followingSelectVO.setLastId(lastId);
        return followingSelectVO;
    }

    @Override
    public UserInfoVo getUserInfo(Long userId) {
        // userId?????? ????????????????????????
        if (userId == null) {
            User user = (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
            userId = user.getId();
        }

        UserInfoVo userInfoVo = new UserInfoVo();
        // SimpleUser??????
        User simpleUser = this.getSimpleUserById(userId);
        userInfoVo.setId(simpleUser.getId());
        userInfoVo.setNickname(simpleUser.getNickname());
        userInfoVo.setGender(simpleUser.getGender());
        userInfoVo.setAvatar(simpleUser.getAvatar());
        userInfoVo.setRoles(simpleUser.getRoles());

        // followingsCount???followersCount
        Long followingsCount = redisTemplate.opsForSet().size(RedisConst.USER_FOLLOWING_KEY + userId);
        Long followersCount = redisTemplate.opsForSet().size(RedisConst.USER_FOLLOWER_KEY + userId);
        followingsCount = followingsCount != null ? followingsCount : 0;
        followersCount = followersCount != null ? followersCount : 0;
        userInfoVo.setFollowingsCount(followingsCount);
        userInfoVo.setFollowersCount(followersCount);

        return userInfoVo;
    }

    @Override
    public String uploadAvatar(MultipartFile file) throws IOException {
        /* FTP??????????????? */
        String filePath = fileService.uploadFile(file);

        /* ?????????MySQL */
        User user = (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        Long myUserId = user.getId();
        User t = new User();
        t.setId(myUserId);
        t.setAvatar(filePath);
        userMapper.updateById(t);

        /* ???????????????????????????????????? */
        redisTemplate.delete(CacheConst.CACHE_USER_KEY + myUserId);
        userCache.invalidate(CacheConst.CACHE_USER_KEY + myUserId);

        return filePath;
    }

    @Override
    public void updateUserInfo(UserUpdateDTO userUpdateDTO) throws JsonProcessingException {
        String nickname = userUpdateDTO.getNickname();
        Integer gender = userUpdateDTO.getGender();
        if (StrUtil.isBlank(nickname) || nickname.length() > 20) {
            throw new RuntimeException("???????????????????????????");
        }

        /* ??????????????? */
        userUpdateDTO.setNickname(SuzumiyaUtils.replaceAllSensitiveWords(userUpdateDTO.getNickname()));

        /* ?????????MySQL */
        User user = (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        Long myUserId = user.getId();
        User t = new User();
        t.setId(myUserId);
        if (StrUtil.isNotBlank(nickname)) {
            t.setNickname(nickname);
        }
        t.setGender(gender);
        userMapper.updateById(t);

        /* ??????Caffeine?????? */
        redisTemplate.delete(CacheConst.CACHE_USER_KEY + myUserId);
        userCache.invalidate(CacheConst.CACHE_USER_KEY + myUserId);
    }

    private boolean checkLogin(Serializable userId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(RedisConst.LOGIN_USER_KEY
                + userId));
    }
}
