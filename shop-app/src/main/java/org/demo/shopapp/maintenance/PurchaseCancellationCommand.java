package org.demo.shopapp.maintenance;

import org.demo.shopapp.cart.*;
import org.demo.shopapp.catalog.ProductRepository;
import org.demo.shopapp.config.*;
import org.demo.shopapp.order.*;
import org.demo.shopapp.payment.*;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;
import javax.sql.DataSource;
import java.util.*;

/** Fixed local CC job. No Boot, Flyway, web server, scheduling, arbitrary SQL or operator impersonation. */
public final class PurchaseCancellationCommand {
    @EnableTransactionManagement static class Transactions{}
    public static void main(String[] args){
        int exitCode=0;
        try(var context=new AnnotationConfigApplicationContext()){
            var options=new HashMap<String,String>();
            for(var arg:args){var split=arg.split("=",2);if(split.length!=2||!split[0].startsWith("--")||options.putIfAbsent(split[0].substring(2),split[1])!=null)throw new IllegalArgumentException();}
            if(!options.keySet().equals(Set.of("profile","operation","orderId","requestId","reason","confirmation")) || !"dev".equals(options.get("profile"))
                    || !Set.of("purchase-cancel","purchase-refresh").contains(options.get("operation"))
                    || !("purchase-cancel".equals(options.get("operation"))?"CANCEL":"REFRESH").equals(options.get("confirmation")))throw new IllegalArgumentException();
            long id=Long.parseLong(options.get("orderId"));if(id<1)throw new IllegalArgumentException();
            var request=UUID.fromString(options.get("requestId"));if(!request.toString().equals(options.get("requestId")))throw new IllegalArgumentException();
            var env=System.getenv();var host=env.getOrDefault("SHOP_POSTGRES_HOST","");var port=env.getOrDefault("SHOP_POSTGRES_PORT","");var db=env.getOrDefault("SHOP_POSTGRES_DB","");
            if(!Set.of("127.0.0.1","localhost","::1").contains(host)||!port.matches("[0-9]{4,5}")||Integer.parseInt(port)>65535||!db.matches("[A-Za-z0-9_]+"))throw new IllegalArgumentException();
            var ds=new DriverManagerDataSource("jdbc:postgresql://"+(host.equals("::1")?"[::1]":host)+":"+port+"/"+db+"?connectTimeout=5&socketTimeout=15",env.get("SHOP_POSTGRES_USER"),env.get("SHOP_POSTGRES_PASSWORD"));
            var bank=new ShopBankProperties();bank.setApiKey(env.getOrDefault("BANK_API_KEY",""));bank.setApiKeyId(env.getOrDefault("BANK_API_KEY_ID","legacy"));
            var bankUrl=java.net.URI.create(env.getOrDefault("BANK_API_URL","http://127.0.0.1:8080"));
            if(!"http".equals(bankUrl.getScheme())||!Set.of("127.0.0.1","localhost").contains(bankUrl.getHost())||bankUrl.getRawUserInfo()!=null||bankUrl.getRawQuery()!=null||bankUrl.getRawFragment()!=null||!Set.of("","/").contains(bankUrl.getPath()))throw new IllegalArgumentException();
            bank.setBaseUrl(bankUrl.toString());
            var provider=new ShopPaymentProperties();provider.setProvider(env.getOrDefault("SHOP_PAYMENT_PROVIDER","bank-demo"));provider.setApiKey(env.getOrDefault("SHOP_PAYMENT_API_KEY",""));
            if(!provider.getApiKey().isBlank() && !provider.getApiKey().startsWith("sk_test_"))throw new IllegalArgumentException();
            var properties=new ShopOrderProperties();properties.setMerchantId(env.getOrDefault("SHOP_MERCHANT_ID",""));properties.setMerchantAccountNumber(env.getOrDefault("SHOP_MERCHANT_ACCOUNT_NUMBER",""));properties.requireMerchantId();
            context.register(Transactions.class);
            context.registerBean(DataSource.class,()->ds);context.registerBean(JdbcTemplate.class,()->new JdbcTemplate(ds));
            context.registerBean(org.springframework.transaction.PlatformTransactionManager.class,()->new DataSourceTransactionManager(ds));
            context.registerBean(ShopOrderProperties.class,()->properties);context.registerBean(ShopBankProperties.class,()->bank);context.registerBean(ShopPaymentProperties.class,()->provider);
            var factory=new org.springframework.http.client.JdkClientHttpRequestFactory(java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(3)).build());factory.setReadTimeout(java.time.Duration.ofSeconds(10));
            context.registerBean(RestClient.class,()->RestClient.builder().baseUrl(bank.getBaseUrl()).requestFactory(factory).build());
            context.registerBean(RestClient.Builder.class,()->RestClient.builder());
            context.register(CartRepository.class,ProductRepository.class,org.demo.shopapp.cart.PurchaseIdentityService.class,CartService.class,
                    OrderRepository.class,PaymentAttemptRepository.class,PaymentAttemptService.class,OrderCartCleanupService.class,
                    BankAuthorizationClient.class,StripeCheckoutClient.class,PaymentResolutionService.class,PurchaseCancellationService.class);
            context.refresh();
            var jdbc=context.getBean(JdbcTemplate.class);
            if("purchase-cancel".equals(options.get("operation")) && !Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT EXISTS(SELECT 1 FROM flyway_schema_history WHERE version='17' AND success)",Boolean.class))){
                System.err.println("Wymagana migracja V17 sklepu. Uruchom aktualną aplikację sklepu i sprawdź Historię migracji przed anulowaniem i zamknięciem sprawy.");
                throw new IllegalStateException();
            }
            if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT merchant_id=? AND purchase_id IS NOT NULL FROM shop_orders WHERE id=?",Boolean.class,properties.requireMerchantId(),id)))throw new IllegalArgumentException();
            var customer=jdbc.queryForObject("SELECT customer_id FROM shop_orders WHERE id=?",String.class,id);
            var service=context.getBean(PurchaseCancellationService.class);
            var result="purchase-cancel".equals(options.get("operation"))?service.cancel(id,customer,request,"LOCAL_CC_DEV",options.get("reason")):service.refresh(id,customer);
            System.out.println(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(result));
            if("purchase-cancel".equals(options.get("operation")) && !result.caseClosed()){
                System.err.println("NIE ZAMKNIĘTO SPRAWY: sprawdź status i komunikat wyniku. REVIEW wymaga dowodów; PAID wymaga osobnego procesu zwrotu. Nie potwierdzono zamknięcia jako CANCELLED.");exitCode=2;
            }
        }catch(Exception failure){System.err.println("Nie potwierdzono zakończenia operacji zakupu. Sprawdź bieżący stan i audyt przed ponowieniem. Nie wysyłano nowego obciążenia ani zwrotu.");exitCode=1;}
        if(exitCode!=0)System.exit(exitCode);
    }
}
