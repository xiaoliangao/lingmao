package com.lingmao.scm.common.jobs;

public interface OutboxWork {
    long id();
    String leaseToken();
    String requestId();
    int attempt();
    /** 两批之间检查：取消了没有、租约还归不归自己、是不是在停机；不在长业务事务里检查。 */
    void checkpoint();
    /** 最后那条短的业务更新和消息确认，在持有租约行锁时一起提交。 */
    void complete(Runnable businessUpdate);
}
