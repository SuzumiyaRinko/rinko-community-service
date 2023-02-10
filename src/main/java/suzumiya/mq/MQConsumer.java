package suzumiya.mq;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.RedisTemplate;
import suzumiya.constant.CommonConst;
import suzumiya.constant.MQConstant;
import suzumiya.constant.RedisConst;
import suzumiya.mapper.PostMapper;
import suzumiya.model.pojo.Post;
import suzumiya.model.pojo.User;
import suzumiya.repository.PostRepository;
import suzumiya.util.MailUtils;
import suzumiya.util.WordTreeUtils;

import javax.mail.MessagingException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Configuration
@Slf4j
public class MQConsumer {

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private PostMapper postMapper;

    @Autowired
    private PostRepository postRepository;

    /* 监听用户注册接口 */
    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(name = MQConstant.USER_REGISTER_QUEUE),
            exchange = @Exchange(name = MQConstant.SERVICE_DIRECT, type = ExchangeTypes.DIRECT, delayed = "true"),
            key = {MQConstant.USER_REGISTER_KEY}
    ))
    public void listenUserRegisterQueue(User newUser) throws MessagingException {
        /* 发送邮件到用户邮箱 */
        String toMail = newUser.getUsername();
        String activationURL = CommonConst.PREFIX_ACTIVATION_URL + newUser.getActivationUUID();
        String text = CommonConst.HTML_ACTIVATION.replaceAll("<xxxxx>", toMail).replaceAll("<yyyyy>", activationURL);

        MailUtils.sendMail(CommonConst.MAIL_FROM, List.of(toMail), "Rinko-Community | 账号激活", null, text, null);

        /* 30mins激活时间 */
        redisTemplate.opsForValue().set(RedisConst.ACTIVATION_USER_KEY + newUser.getActivationUUID(), newUser.getId(), 30L, TimeUnit.MINUTES); // 30mins

        log.debug("正在注册 username={} ", newUser.getUsername());
    }

    /* 监听Post新增接口 */
    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(name = MQConstant.POST_INSERT_QUEUE),
            exchange = @Exchange(name = MQConstant.SERVICE_DIRECT, type = ExchangeTypes.DIRECT, delayed = "true"),
            key = {MQConstant.POST_INSERT_KEY}
    ))
    public void listenPostInsertQueue(Post post) {
        /* 过滤敏感词 */
        post.setTitle(WordTreeUtils.replaceAllSensitiveWords(post.getTitle()));
        post.setContent(WordTreeUtils.replaceAllSensitiveWords(post.getContent()));

        /* 新增post到MySQL */
        // 把tagIDs转换为tags
        List<Integer> tagIDs = post.getTagIDs();
        int tags = 0;
        for (Integer tagID : tagIDs) {
            tags += Math.pow(2, tagID - 1);
        }
        post.setTags(tags);
        // 新增post到MySQL
        post.setCreateTime(LocalDateTime.now());
        postMapper.insert(post);

        /* 新增post到ES */
        postRepository.save(post);

        /* 添加到带算分Post的Set集合 */
        redisTemplate.opsForSet().add(RedisConst.POST_SCORE_UPDATE_KEY, post.getId());

        log.debug("正在新增帖子 title={} ", post.getTitle());
    }

    // DelayQueue：监听用户激活时间是否结束
//    @RabbitListener(bindings = @QueueBinding(
//            value = @Queue(name = MQConstant.ACTIVATION_QUEUE),
//            exchange = @Exchange(name = MQConstant.DELAY_DIRECT, type = ExchangeTypes.DIRECT, delayed = "true"),
//            key = {MQConstant.ACTIVATION_KEY}
//    ))
//    public void listenActivationQueue(Message message) {
//        String uuid = new String(message.getBody());
//        Integer userId = (Integer) redisTemplate.opsForValue().get(RedisConst.ACTIVATION_USER_KEY + uuid);
//        User user = userMapper.getUserById((long) userId);
//        userMapper.deleteById(userId);
//
//        log.debug("正在取消 userId={} 的激活资格", user.getUsername());
//
//        /* 取消激活资格 */
//        redisTemplate.delete(RedisConst.ACTIVATION_USER_KEY + uuid);
//    }
}
