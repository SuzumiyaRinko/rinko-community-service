package suzumiya.filter;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpStatus;
import cn.hutool.jwt.JWT;
import cn.hutool.jwt.JWTUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import suzumiya.constant.RedisConst;
import suzumiya.model.pojo.User;
import suzumiya.model.vo.BaseResponse;
import suzumiya.util.ResponseGenerator;
import suzumiya.util.WebUtils;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component(value = "jwtFilter")
@Slf4j
public class JwtFilter extends OncePerRequestFilter {

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Resource
    private ObjectMapper objectMapper;

    private final List<String> anonymousURIs = new ArrayList<>();

    @PostConstruct
    private void init() {
        anonymousURIs.add(SERVLET_CONTEXT + "/verifyCode");
        anonymousURIs.add(SERVLET_CONTEXT + "/user/login");
        anonymousURIs.add(SERVLET_CONTEXT + "/user/loginAnonymously");
        anonymousURIs.add(SERVLET_CONTEXT + "/user/register");
        anonymousURIs.add(SERVLET_CONTEXT + "/user/activation");
    }

    private static final String TOKEN_KEY = "114514"; // Token??????
    private static final String SERVLET_CONTEXT = "";
    private static final String WSCHAT_URI_PREFIX = SERVLET_CONTEXT + "/wsChat"; // wsChat???Uri

    @Override
    protected void doFilterInternal(@NotNull HttpServletRequest request, @NotNull HttpServletResponse response, @NotNull FilterChain filterChain) throws ServletException, IOException {
        String uri = request.getRequestURI();
        if (uri.equals("/favicon.ico")) {
            return; // ???????????????????????? html??????????????????/favicon.ico
        }

        /* ?????????????????????????????? */
        for (String anonymousURI : anonymousURIs) {
            if (uri.equals(anonymousURI) || uri.startsWith(anonymousURI)) {
                filterChain.doFilter(request, response);
                return;
            }
        }

        /* ?????? */
        boolean wsFlag = false;
        String token;
        if (uri.startsWith(WSCHAT_URI_PREFIX)) {
            token = request.getHeader("Sec-WebSocket-Protocol");
            wsFlag = true;
        } else {
            token = request.getHeader("Authorization");
        }
        // ??????
        if (StrUtil.isBlank(token)) {
            BaseResponse<Object> baseResponse = ResponseGenerator.returnError(HttpStatus.HTTP_UNAUTHORIZED, "?????????????????????");
            WebUtils.renderString(response, objectMapper.writeValueAsString(baseResponse));
            return;
        }
        // ??????
        boolean verify = JWTUtil.verify(token, TOKEN_KEY.getBytes(StandardCharsets.UTF_8));
        if (!verify) {
            BaseResponse<Object> baseResponse = ResponseGenerator.returnError(HttpStatus.HTTP_UNAUTHORIZED, "?????????????????????");
            WebUtils.renderString(response, objectMapper.writeValueAsString(baseResponse));
            return;
        }
        JWT jwt = JWTUtil.parseToken(token);
        long userId = (long) (Integer) jwt.getPayload("userId");
        // ???Redis?????????????????????
        User user = new User();
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(RedisConst.LOGIN_USER_KEY + userId);
        if (ObjectUtil.isEmpty(entries)) {
            BaseResponse<Object> baseResponse = ResponseGenerator.returnError(HttpStatus.HTTP_UNAUTHORIZED, "?????????????????????");
            WebUtils.renderString(response, objectMapper.writeValueAsString(baseResponse));
            return;
        }
        BeanUtil.fillBeanWithMap(entries, user, null);
        // ??????SecurityContextHolder
        Authentication authentication = new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(authentication);
        // ??????????????????TTL
        redisTemplate.expire(RedisConst.LOGIN_USER_KEY + userId, 30L, TimeUnit.MINUTES);

        /* ?????????ws?????????????????????myUserId?????????Token??????userId?????? */
        if (wsFlag) {
            String[] split = uri.split("/");
            String wsUserId = split[split.length - 1];
            if (!StrUtil.equals(String.valueOf(userId), wsUserId)) {
                BaseResponse<Object> baseResponse = ResponseGenerator.returnError(HttpStatus.HTTP_UNAUTHORIZED, "ws??????????????????");
                WebUtils.renderString(response, objectMapper.writeValueAsString(baseResponse));
                return;
            }
        }

        /* ?????? */
        filterChain.doFilter(request, response);

        /* ?????????ws????????????????????????token??????Sec-WebSocket-Protocol??? */
        if (wsFlag) {
            response.setHeader("Sec-WebSocket-Protocol", token);
        }
    }
}
