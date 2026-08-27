package com.feisheng.bot.core.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.feisheng.bot.core.entity.BotConversation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface BotConversationMapper extends BaseMapper<BotConversation> {
    @Update("""
        UPDATE bot_conversation
        SET dialog_state = #{dialogState},
            dialog_state_version = dialog_state_version + 1
        WHERE id = #{conversationId}
          AND dialog_state_version = #{expectedVersion}
          AND deleted = 0
        """)
    int updateDialogState(@Param("conversationId") Long conversationId,
                          @Param("dialogState") String dialogState,
                          @Param("expectedVersion") long expectedVersion);
}
