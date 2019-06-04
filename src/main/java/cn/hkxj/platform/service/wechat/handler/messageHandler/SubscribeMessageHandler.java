package cn.hkxj.platform.service.wechat.handler.messageHandler;

import cn.hkxj.platform.builder.TextBuilder;
import cn.hkxj.platform.pojo.ScheduleTask;
import cn.hkxj.platform.pojo.constant.SubscribeScene;
import cn.hkxj.platform.service.ScheduleTaskService;
import com.google.common.base.Splitter;
import lombok.extern.slf4j.Slf4j;
import me.chanjar.weixin.common.exception.WxErrorException;
import me.chanjar.weixin.common.session.WxSessionManager;
import me.chanjar.weixin.mp.api.WxMpMessageHandler;
import me.chanjar.weixin.mp.api.WxMpService;
import me.chanjar.weixin.mp.bean.message.WxMpXmlMessage;
import me.chanjar.weixin.mp.bean.message.WxMpXmlOutMessage;
import org.apache.poi.ss.formula.functions.LinearRegressionFunction;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.Map;
import java.util.Objects;
import java.util.stream.StreamSupport;

/**
 * @author Yuki
 * @date 2019/5/25 15:08
 */
@Slf4j
@Component
public class SubscribeMessageHandler implements WxMpMessageHandler{
    private static final String PATTERN = "格式不正确:\n\n订阅格式:\n订阅 关键字 如：明日课表";
    private static Splitter SPLITTER = Splitter.on(" ").trimResults().omitEmptyStrings();
    private static final int VALID_LENGTH = 2;


    @Resource
    private ScheduleTaskService scheduleTaskService;
    @Resource
    private TextBuilder textBuilder;

    @Override
    public WxMpXmlOutMessage handle(WxMpXmlMessage wxMessage, Map<String, Object> context, WxMpService wxMpService, WxSessionManager sessionManager) throws WxErrorException {
        String scene = parseContent(wxMessage.getContent());
        if(!Objects.isNull(scene)){
            String openid = wxMessage.getFromUser();
            String appid = wxMpService.getWxMpConfigStorage().getAppId();
            ScheduleTask scheduleTask = new ScheduleTask(appid, openid, scene);
            //判断有没有相应的订阅记录，有就更新记录，没有就插入一条记录
            scheduleTaskService.checkAndSetSubscribeStatus(scheduleTask, true);
            return textBuilder.build("订阅成功", wxMessage, wxMpService);
        }
        return textBuilder.build(PATTERN, wxMessage, wxMpService);
    }

    //解析发送来的消息，同时将关键字对应的场景值返回
    private String parseContent(String content){
        String[] strings = StreamSupport.stream(SPLITTER.split(content).spliterator(), false).toArray(String[]::new);
        if(strings.length == VALID_LENGTH){
            SubscribeScene subscribeScene = SubscribeScene.getSubscribeSceneByChinese(strings[1]);
            if(!Objects.isNull(subscribeScene)){
                return subscribeScene.getScene();
            }
        }
        return null;
    }
}