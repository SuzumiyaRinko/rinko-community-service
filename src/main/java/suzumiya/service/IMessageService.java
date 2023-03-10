package suzumiya.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.fasterxml.jackson.core.JsonProcessingException;
import suzumiya.model.dto.MessageDeleteDTO;
import suzumiya.model.dto.MessageInsertDTO;
import suzumiya.model.dto.MessageSelectDTO;
import suzumiya.model.dto.MessageSetIsReadDTO;
import suzumiya.model.pojo.Message;
import suzumiya.model.vo.MessageSelectVO;

public interface IMessageService extends IService<Message> {

    /* 发信息 */
    void saveMessage(MessageInsertDTO messageInsertDTO) throws JsonProcessingException;

    /* 获取当前用户私信列表的总未读消息数 */
    Integer notReadCount(Long myUserId);

    /* 查询系统消息或对话列表 */
    // 如果查询的是对话列表，那么获得的是每个对话对象的第一条消息
    MessageSelectVO getMessages(MessageSelectDTO messageSelectDTO);

    /* 查询对话消息 */
    MessageSelectVO getChatMessages(MessageSelectDTO messageSelectDTO);

    void setIsRead(MessageSetIsReadDTO messageSetIsReadDTO);

    void deleteMessage(MessageDeleteDTO messageDeleteDTO);
}
