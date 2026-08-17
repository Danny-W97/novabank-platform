package org.demo.shopapp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "shop.rate-limit")
public class ShopRateLimitProperties {

    private boolean enabled = true;
    private int windowSeconds = 60;
    private int loginIpPerWindow = 20;
    private int loginAccountPerWindow = 5;
    private int checkoutIpPerWindow = 30;
    private int checkoutUserPerWindow = 15;
    private int paymentIpPerWindow = 20;
    private int paymentUserPerWindow = 10;
    private int webhookIpPerWindow = 60;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public int getWindowSeconds() { return windowSeconds; }
    public void setWindowSeconds(int value) { windowSeconds = value; }
    public int getLoginIpPerWindow() { return loginIpPerWindow; }
    public void setLoginIpPerWindow(int value) { loginIpPerWindow = value; }
    public int getLoginAccountPerWindow() { return loginAccountPerWindow; }
    public void setLoginAccountPerWindow(int value) { loginAccountPerWindow = value; }
    public int getCheckoutIpPerWindow() { return checkoutIpPerWindow; }
    public void setCheckoutIpPerWindow(int value) { checkoutIpPerWindow = value; }
    public int getCheckoutUserPerWindow() { return checkoutUserPerWindow; }
    public void setCheckoutUserPerWindow(int value) { checkoutUserPerWindow = value; }
    public int getPaymentIpPerWindow() { return paymentIpPerWindow; }
    public void setPaymentIpPerWindow(int value) { paymentIpPerWindow = value; }
    public int getPaymentUserPerWindow() { return paymentUserPerWindow; }
    public void setPaymentUserPerWindow(int value) { paymentUserPerWindow = value; }
    public int getWebhookIpPerWindow() { return webhookIpPerWindow; }
    public void setWebhookIpPerWindow(int value) { webhookIpPerWindow = value; }
}
