package com.aiwan.ace.aiwan.Imservice.service;

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;

import com.aiwan.ace.aiwan.Config.SysConstant;
import com.aiwan.ace.aiwan.Db.DBInterface;
import com.aiwan.ace.aiwan.Db.entity.MessageEntity;
import com.aiwan.ace.aiwan.Db.sp.ConfigurationSp;
import com.aiwan.ace.aiwan.Db.sp.LoginSp;
import com.aiwan.ace.aiwan.Imservice.event.LoginEvent;
import com.aiwan.ace.aiwan.Imservice.event.PriorityEvent;
import com.aiwan.ace.aiwan.Imservice.manager.IMContactManager;
import com.aiwan.ace.aiwan.Imservice.manager.IMGroupManager;
import com.aiwan.ace.aiwan.Imservice.manager.IMHeartBeatManager;
import com.aiwan.ace.aiwan.Imservice.manager.IMLoginManager;
import com.aiwan.ace.aiwan.Imservice.manager.IMMessageManager;
import com.aiwan.ace.aiwan.Imservice.manager.IMNotificationManager;
import com.aiwan.ace.aiwan.Imservice.manager.IMReconnectManager;
import com.aiwan.ace.aiwan.Imservice.manager.IMSessionManager;
import com.aiwan.ace.aiwan.Imservice.manager.IMSocketManager;
import com.aiwan.ace.aiwan.Imservice.manager.IMUnreadMsgManager;
import com.aiwan.ace.aiwan.Utils.ImageLoaderUtil;
import com.aiwan.ace.aiwan.Utils.Logger;

import org.greenrobot.eventbus.EventBus;

/**
 * Created by ACE on 2016/3/10.
 */

/**
 * IMService 负责所有IMManager的初始化与reset
 * 并且Manager的状态的改变 也会影响到IMService的操作
 * 备注: 有些服务应该在LOGIN_OK 之后进行
 * todo IMManager reflect or just like  ctx.getSystemService()
 */
public class IMService extends Service {
    private static final String Tag = "IMService";

    private Logger logger = Logger.getLogger(IMService.class);

    /**binder*/
    private IMServiceBinder binder = new IMServiceBinder();
    public class IMServiceBinder extends Binder {
        public IMService getService() {
            return IMService.this;
        }
    }

    @Override
    public IBinder onBind(Intent arg0) {
        logger.i(Tag, "IMService onBind");
        return binder;
    }

    //所有的管理类
    private IMSocketManager socketMgr = IMSocketManager.instance();
    private IMLoginManager loginMgr = IMLoginManager.instance();
    private IMContactManager contactMgr = IMContactManager.instance();
    private IMGroupManager groupMgr = IMGroupManager.instance();
    private IMMessageManager messageMgr = IMMessageManager.instance();
    private IMSessionManager sessionMgr = IMSessionManager.instance();
    private IMReconnectManager reconnectMgr = IMReconnectManager.instance();
    private IMUnreadMsgManager unReadMsgMgr = IMUnreadMsgManager.instance();
    private IMNotificationManager notificationMgr = IMNotificationManager.instance();
    private IMHeartBeatManager heartBeatManager = IMHeartBeatManager.instance();

    private ConfigurationSp configSp;
    private LoginSp loginSp = LoginSp.instance();
    private DBInterface dbInterface = DBInterface.instance();

    @Override
    public void onCreate() {
        logger.i(Tag, "IMService onCreate");
        super.onCreate();
        EventBus.getDefault().register(this);
        // make the service foreground, so stop "360 yi jian qingli"(a clean
        // tool) to stop our app
        // todo eric study wechat's mechanism, use a better solution
        startForeground((int) System.currentTimeMillis(), new Notification());
    }

    @Override
    public void onDestroy() {
        logger.i(Tag, "IMService onDestroy");
        // todo 在onCreate中使用startForeground
        // 在这个地方是否执行 stopForeground呐
        EventBus.getDefault().unregister(this);
        handleLoginout();
        // DB的资源的释放
        dbInterface.close();

        IMNotificationManager.instance().cancelAllNotifications();
        super.onDestroy();
    }

    /**收到消息需要上层的activity判断 {MessageActicity onEvent(PriorityEvent event)}，这个地方是特殊分支*/
    public void onEvent(PriorityEvent event){
        switch (event.event){
            case MSG_RECEIVED_MESSAGE:{
                MessageEntity entity = (MessageEntity) event.object;
                /**非当前的会话*/
                logger.d(Tag, "messageactivity#not this session msg -> id:%s", entity.getFromId());
                messageMgr.ackReceiveMsg(entity);
                unReadMsgMgr.add(entity);
            }break;
        }
    }

    // EventBus 事件驱动
    public void onEvent(LoginEvent event){
        switch (event){
            case LOGIN_OK:
                onNormalLoginOk();break;
            case LOCAL_LOGIN_SUCCESS:
                onLocalLoginOk();
                break;
            case  LOCAL_LOGIN_MSG_SERVICE:
                onLocalNetOk();
                break;
            case LOGIN_OUT:
                handleLoginout();break;
        }
    }

    // 负责初始化 每个manager
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        logger.i(Tag, "IMService onStartCommand");
        //应用开启初始化 下面这几个怎么释放 todo
        Context ctx = getApplicationContext();
        loginSp.init(ctx);
        // 放在这里还有些问题 todo
        socketMgr.onStartIMManager(ctx);
        loginMgr.onStartIMManager(ctx);
        contactMgr.onStartIMManager(ctx);
        messageMgr.onStartIMManager(ctx);
        groupMgr.onStartIMManager(ctx);
        sessionMgr.onStartIMManager(ctx);
        unReadMsgMgr.onStartIMManager(ctx);
        notificationMgr.onStartIMManager(ctx);
        reconnectMgr.onStartIMManager(ctx);
        heartBeatManager.onStartIMManager(ctx);

        ImageLoaderUtil.initImageLoaderConfig(ctx);
        return START_STICKY;
    }


    /**
     * 用户输入登陆流程
     * userName/pwd -> reqMessage ->connect -> loginMessage ->loginSuccess
     */
    private void onNormalLoginOk() {
        logger.d(Tag, "imservice#onLogin Successful");
        //初始化其他manager todo 这个地方注意上下文的清除
        Context ctx = getApplicationContext();
        int loginId =  loginMgr.getLoginId();
        configSp = ConfigurationSp.instance(ctx,loginId);
        dbInterface.initDbHelp(ctx,loginId);

        contactMgr.onNormalLoginOk();
        sessionMgr.onNormalLoginOk();
        groupMgr.onNormalLoginOk();
        unReadMsgMgr.onNormalLoginOk();

        reconnectMgr.onNormalLoginOk();
        //依赖的状态比较特殊
        messageMgr.onLoginSuccess();
        notificationMgr.onLoginSuccess();
        heartBeatManager.onloginNetSuccess();
        // 这个时候loginManage中的localLogin 被置为true
    }


    /**
     * 自动登陆/离线登陆成功
     * autoLogin -> DB(loginInfo,loginId...) -> loginSucsess
     */
    private void onLocalLoginOk(){
        Context ctx = getApplicationContext();
        int loginId =  loginMgr.getLoginId();
        configSp = ConfigurationSp.instance(ctx,loginId);
        dbInterface.initDbHelp(ctx,loginId);

        contactMgr.onLocalLoginOk();
        groupMgr.onLocalLoginOk();
        sessionMgr.onLocalLoginOk();
        reconnectMgr.onLocalLoginOk();
        notificationMgr.onLoginSuccess();
        messageMgr.onLoginSuccess();
    }

    /**
     * 1.从本机加载成功之后，请求MessageService建立链接成功(loginMessageSuccess)
     * 2. 重练成功之后
     */
    private void onLocalNetOk(){
        /**为了防止逗比直接把loginId与userName的对应直接改了,重刷一遍*/
        Context ctx = getApplicationContext();
        int loginId =  loginMgr.getLoginId();
        configSp = ConfigurationSp.instance(ctx,loginId);
        dbInterface.initDbHelp(ctx,loginId);

        contactMgr.onLocalNetOk();
        groupMgr.onLocalNetOk();
        sessionMgr.onLocalNetOk();
        unReadMsgMgr.onLocalNetOk();
        reconnectMgr.onLocalNetOk();
        heartBeatManager.onloginNetSuccess();
    }

    private void handleLoginout() {
        logger.d(Tag, "imservice#handleLoginout");

        // login需要监听socket的变化,在这个地方不能释放，设计上的不合理?
        socketMgr.reset();
        loginMgr.reset();
        contactMgr.reset();
        messageMgr.reset();
        groupMgr.reset();
        sessionMgr.reset();
        unReadMsgMgr.reset();
        notificationMgr.reset();
        reconnectMgr.reset();
        heartBeatManager.reset();
        configSp = null;
        EventBus.getDefault().removeAllStickyEvents();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        logger.d(Tag, "imservice#onTaskRemoved");
        // super.onTaskRemoved(rootIntent);
        this.stopSelf();
    }

    /**-----------------get/set 的实体定义---------------------*/
    public IMLoginManager getLoginManager() {
        return loginMgr;
    }

    public IMContactManager getContactManager() {
        return contactMgr;
    }

    public IMMessageManager getMessageManager() {
        return messageMgr;
    }


    public IMGroupManager getGroupManager() {
        return groupMgr;
    }

    public IMSessionManager getSessionManager() {
        return sessionMgr;
    }

    public IMReconnectManager getReconnectManager() {
        return reconnectMgr;
    }


    public IMUnreadMsgManager getUnReadMsgManager() {
        return unReadMsgMgr;
    }

    public IMNotificationManager getNotificationManager() {
        return notificationMgr;
    }

    public DBInterface getDbInterface() {
        return dbInterface;
    }

    public ConfigurationSp getConfigSp() {
        return configSp;
    }

    public LoginSp getLoginSp() {
        return loginSp;
    }

}
