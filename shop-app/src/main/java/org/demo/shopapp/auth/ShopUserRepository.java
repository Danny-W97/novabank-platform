package org.demo.shopapp.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Statement;
import java.util.Optional;

@Repository
public class ShopUserRepository {

    private final JdbcTemplate jdbcTemplate;

    public ShopUserRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<ShopUserDetails> findByEmail(String email) {
        return jdbcTemplate.query(
                        """
                        SELECT id, email, password_hash, customer_id, active, role
                        FROM shop_users
                        WHERE email = ?
                        """,
                        (rs, rowNum) -> new ShopUserDetails(
                                rs.getLong("id"),
                                rs.getString("email"),
                                rs.getString("password_hash"),
                                rs.getString("customer_id"),
                                rs.getBoolean("active"),
                                rs.getString("role")
                        ),
                        email
                )
                .stream()
                .findFirst();
    }

    public boolean existsByEmail(String email) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM shop_users WHERE email = ?)",
                Boolean.class,
                email
        ));
    }

    public void create(String email, String passwordHash, String customerId) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            var statement = connection.prepareStatement(
                    """
                    INSERT INTO shop_users (
                        email, password_hash, customer_id
                    ) VALUES (?, ?, ?)
                    """,
                    Statement.RETURN_GENERATED_KEYS
            );
            statement.setString(1, email);
            statement.setString(2, passwordHash);
            statement.setString(3, customerId);
            return statement;
        }, keyHolder);
    }
}
