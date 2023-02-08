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
        log.info("JwtFilter.init()");
        anonymousURIs.add("/user/login");
        anonymousURIs.add("/user/register");
        anonymousURIs.add("/user/activation");
        anonymousURIs.add("/test2");
    }

    private static final String TOKEN_KEY = "114514"; // Token密钥

    @Override
    protected void doFilterInternal(@NotNull HttpServletRequest request, @NotNull HttpServletResponse response, @NotNull FilterChain filterChain) throws ServletException, IOException {
        String uri = request.getRequestURI();
        if(uri.equals("/favicon.ico")) {
            return; // 在返回激活页面后 html会来后端请求/favicon.ico
        }

        /* 可匿名访问，直接放行 */
        for (String anonymousURI : anonymousURIs) {
            if(uri.equals(anonymousURI) || uri.startsWith(anonymousURI)) {
                filterChain.doFilter(request, response);
                return;
            }
        }

        /* 验签 */
        String token = request.getHeader("Authorization").substring("Bearer ".length());
        // 判空
        if(StrUtil.isBlank(token)) {
            BaseResponse<Object> baseResponse = ResponseGenerator.returnError(HttpStatus.HTTP_UNAUTHORIZED, "身份认证未通过");
            WebUtils.renderString(response, objectMapper.writeValueAsString(baseResponse));
            return;
        }
        // 验签
        boolean verify = JWTUtil.verify(token, TOKEN_KEY.getBytes(StandardCharsets.UTF_8));
        if(!verify) {
            BaseResponse<Object> baseResponse = ResponseGenerator.returnError(HttpStatus.HTTP_UNAUTHORIZED, "身份认证未通过");
            WebUtils.renderString(response, objectMapper.writeValueAsString(baseResponse));
            return;
        }
        JWT jwt = JWTUtil.parseToken(token);
        Long userId = (Long) jwt.getPayload("userId");
        // 从Redis中获取用户信息
        User user = new User();
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(RedisConst.LOGIN_USER_KEY + userId);
        if(ObjectUtil.isEmpty(entries)) {
            BaseResponse<Object> baseResponse = ResponseGenerator.returnError(HttpStatus.HTTP_UNAUTHORIZED, "用户信息已过期");
            WebUtils.renderString(response, objectMapper.writeValueAsString(baseResponse));
            return;
        }
        BeanUtil.fillBeanWithMap(entries, user, null);
        // 存入SecurityContextHolder
        Authentication authentication = new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(authentication);
        // 刷新用户信息TTL
        redisTemplate.expire(RedisConst.LOGIN_USER_KEY + userId, 30L, TimeUnit.MINUTES);

        /* 放行 */
        filterChain.doFilter(request, response);
    }
}
