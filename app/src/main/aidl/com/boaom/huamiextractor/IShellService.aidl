package com.boaom.huamiextractor;

interface IShellService {

    /** 在 root 进程执行一条命令，返回 stdout；失败抛异常 */
    String exec(String cmd);

    /** Shizuku 服务端保留：销毁服务 */
    void destroy() = 16777114;

    /** Shizuku 服务端保留：退出服务 */
    void exit() = 1;
}
