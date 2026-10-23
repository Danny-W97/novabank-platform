package org.demo.bankdemo.maintenance;

import org.demo.bankdemo.security.TotpSecretCipher;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Run through Spring Boot PropertiesLauncher; deliberately does not start Spring, HTTP, Flyway or schedulers. */
public final class MaintenanceCommand {
    private MaintenanceCommand() { }
    public static void main(String[] args) {
        try {
            var options = parse(args);
            var profile = options.get("profile");
            if (!Set.of("dev", "test").contains(profile)) throw new IllegalArgumentException("DEV/TEST required.");
            var source = dataSource(profile, System.getenv());
            if(options.getOrDefault("operation","").startsWith("payment-migration-") || options.getOrDefault("operation","").startsWith("payment-case-")) {
                if(!"dev".equals(profile))throw new IllegalArgumentException("Historical import is local DEV only.");
                var shopEnv=new HashMap<String,String>();
                for(var name:ListHolder.DB_FIELDS)shopEnv.put("POSTGRES_"+name,System.getenv().getOrDefault("SHOP_POSTGRES_"+name,""));
                var shopSource=dataSource("dev",shopEnv);
                if(options.get("operation").startsWith("payment-case-")){
                    var expected=new java.util.HashSet<>(Set.of("profile","operation","orderId","requestId","reason"));
                    switch(options.get("operation")){
                        case "payment-case-plan" -> { }
                        case "payment-case-decision" -> expected.addAll(Set.of("expectedVersion","decision","confirmation"));
                        case "payment-case-apply" -> expected.addAll(Set.of("planDigest","confirmation","backupsVerified","writesPaused"));
                        default -> throw new IllegalArgumentException("Invalid case operation.");
                    }
                    if(!options.keySet().equals(expected))throw new IllegalArgumentException("Unexpected case parameters.");
                    var cases=new PaymentCaseAdministration(source,shopSource,System.getenv());
                    var id=Long.parseLong(options.get("orderId"));var request=java.util.UUID.fromString(options.get("requestId"));
                    if(!request.toString().equals(options.get("requestId")))throw new IllegalArgumentException("Canonical request ID required.");
                    var reason=options.get("reason");
                    Object outcome=switch(options.get("operation")){
                        case "payment-case-plan" -> cases.plan(id,request,reason);
                        case "payment-case-decision" -> cases.decide(id,Long.parseLong(options.get("expectedVersion")),request,options.get("decision"),reason,options.get("confirmation"));
                        case "payment-case-apply" -> cases.apply(id,options.get("planDigest"),request,reason,Boolean.parseBoolean(options.get("backupsVerified")),Boolean.parseBoolean(options.get("writesPaused")),options.get("confirmation"));
                        default -> throw new IllegalArgumentException("Invalid case operation.");
                    };
                    System.out.println(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(outcome));return;
                }
                var migration=new HistoricalPaymentMigration(source,shopSource,System.getenv());
                Object result;
                if("payment-migration-plan".equals(options.get("operation")) && options.keySet().equals(Set.of("profile","operation")))result=migration.plan();
                else if("payment-migration-apply".equals(options.get("operation")) && options.keySet().equals(Set.of("profile","operation","planDigest","confirmation","backupsVerified","writesPaused")))
                    result=migration.apply(options.get("planDigest"),options.get("confirmation"),Boolean.parseBoolean(options.get("backupsVerified")),Boolean.parseBoolean(options.get("writesPaused")));
                else throw new IllegalArgumentException("Invalid migration operation.");
                System.out.println(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(result));return;
            }
            if ("payment-review".equals(options.get("operation"))) {
                if (!options.keySet().equals(Set.of("profile", "operation"))) throw new IllegalArgumentException();
                System.out.println(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
                        new PaymentMigrationReview(source).review()));
                return;
            }
            var prefix = profile.equals("test") ? "TEST_" : "";
            var cipher = new TotpSecretCipher(System.getenv().getOrDefault(prefix+"TOTP_ENCRYPTION_KEY", ""),
                    System.getenv().getOrDefault(prefix+"TOTP_ENCRYPTION_KEYS", ""),
                    System.getenv().getOrDefault(prefix+"TOTP_ENCRYPTION_ACTIVE_ID", "current"));
            var administration = new TotpRotationAdministration(source, cipher);
            var after = Long.parseLong(options.getOrDefault("afterId", "0"));
            var limit = Integer.parseInt(options.getOrDefault("limit", "100"));
            Object result = switch (options.getOrDefault("operation", "")) {
                case "totp-plan" -> administration.plan(after, limit);
                case "totp-apply" -> administration.apply(after, limit, options.get("planDigest"), options.get("confirmation"));
                default -> throw new IllegalArgumentException("Unknown maintenance operation.");
            };
            System.out.println(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(result));
        } catch (Exception exception) {
            // SQL/connection messages can contain credentials or personal data. Never print exception bodies.
            System.err.println("MAINTENANCE_FAILED: check the selected schema, local credentials, range and reviewed plan. Historical import can have committed earlier rows/bank identities: inspect its journal and replay the same approved plan after fixing the cause. Do not delete history or invent a new payment key.");
            System.exit(1);
        }
    }

    static Map<String,String> parse(String[] args) {
        var result = new HashMap<String,String>();
        var allowed = Set.of("profile", "operation", "afterId", "limit", "planDigest", "confirmation", "backupsVerified", "writesPaused", "orderId", "expectedVersion", "decision", "requestId", "reason");
        for (var arg : args) {
            var pair = arg.startsWith("--") ? arg.substring(2).split("=", 2) : new String[0];
            if (pair.length != 2 || !allowed.contains(pair[0]) || result.putIfAbsent(pair[0], pair[1]) != null)
                throw new IllegalArgumentException("Invalid command arguments.");
        }
        return result;
    }

    static DriverManagerDataSource dataSource(String profile, Map<String,String> env) throws Exception {
        String url, user, password;
        if (profile.equals("test")) {
            url = required(env, "TEST_POSTGRES_URL");
            user = required(env, "TEST_POSTGRES_USER"); password = required(env, "TEST_POSTGRES_PASSWORD");
        } else {
            var db = required(env, "POSTGRES_DB");
            var host = required(env, "POSTGRES_HOST");
            var port = required(env, "POSTGRES_PORT");
            if (!db.matches("[A-Za-z0-9_]{1,63}") || !port.matches("[0-9]{1,5}")) throw new IllegalArgumentException();
            url = "jdbc:postgresql://"+host+":"+port+"/"+db;
            user = required(env, "POSTGRES_USER"); password = required(env, "POSTGRES_PASSWORD");
        }
        if (!url.startsWith("jdbc:postgresql://")) throw new IllegalArgumentException();
        var uri = URI.create(url.substring(5));
        if (!Set.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost()) || uri.getUserInfo()!=null
                || uri.getQuery()!=null || uri.getFragment()!=null || uri.getPort()<1 || uri.getPort()>65535)
            throw new IllegalArgumentException("Only explicit localhost database endpoints are allowed.");
        Class.forName("org.postgresql.Driver");
        var source = new DriverManagerDataSource(url, user, password);
        var properties = new java.util.Properties();
        properties.setProperty("connectTimeout", "3"); properties.setProperty("socketTimeout", "20");
        properties.setProperty("ApplicationName", "NovaBank-maintenance"); source.setConnectionProperties(properties);
        return source;
    }

    private static String required(Map<String,String> env, String key) {
        var value = env.get(key);
        if (value==null || value.isBlank()) throw new IllegalArgumentException("Missing local setting.");
        return value;
    }
    private static final class ListHolder {static final String[] DB_FIELDS={"HOST","PORT","DB","USER","PASSWORD"};}
}
