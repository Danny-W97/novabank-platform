package org.demo.bankdemo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.rate-limit")
public class RateLimitProperties {

    private int loginWindowSeconds = 900;
    private int loginIpPerWindow = 20;
    private int loginAccountPerWindow = 5;
    private int authActionWindowSeconds = 60;
    private int authActionIpPerWindow = 10;
    private int transferIpPerWindow = 20;
    private int transferUserPerWindow = 10;

    public int getLoginWindowSeconds() { return loginWindowSeconds; }
    public void setLoginWindowSeconds(int value) { loginWindowSeconds = value; }
    public int getLoginIpPerWindow() { return loginIpPerWindow; }
    public void setLoginIpPerWindow(int value) { loginIpPerWindow = value; }
    public int getLoginAccountPerWindow() { return loginAccountPerWindow; }
    public void setLoginAccountPerWindow(int value) { loginAccountPerWindow = value; }
    public int getAuthActionWindowSeconds() { return authActionWindowSeconds; }
    public void setAuthActionWindowSeconds(int value) { authActionWindowSeconds = value; }
    public int getAuthActionIpPerWindow() { return authActionIpPerWindow; }
    public void setAuthActionIpPerWindow(int value) { authActionIpPerWindow = value; }
    public int getTransferIpPerWindow() { return transferIpPerWindow; }
    public void setTransferIpPerWindow(int value) { transferIpPerWindow = value; }
    public int getTransferUserPerWindow() { return transferUserPerWindow; }
    public void setTransferUserPerWindow(int value) { transferUserPerWindow = value; }
}
