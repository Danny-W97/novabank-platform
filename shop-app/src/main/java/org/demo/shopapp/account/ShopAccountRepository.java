package org.demo.shopapp.account;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class ShopAccountRepository {

    private final JdbcTemplate jdbcTemplate;

    public ShopAccountRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<ProfileRow> findProfile(String customerId) {
        return jdbcTemplate.query(
                        """
                        SELECT email, customer_id, first_name, last_name, phone
                        FROM shop_users
                        WHERE customer_id = ?
                        """,
                        (rs, rowNum) -> new ProfileRow(
                                rs.getString("email"),
                                rs.getString("customer_id"),
                                rs.getString("first_name"),
                                rs.getString("last_name"),
                                rs.getString("phone")
                        ),
                        customerId
                )
                .stream()
                .findFirst();
    }

    public void updateProfile(String customerId, String firstName, String lastName, String phone) {
        jdbcTemplate.update(
                """
                UPDATE shop_users
                SET first_name = ?, last_name = ?, phone = ?, updated_at = CURRENT_TIMESTAMP
                WHERE customer_id = ?
                """,
                firstName, lastName, phone, customerId
        );
    }

    public Optional<AddressRow> findAddress(long userId) {
        return jdbcTemplate.query(
                        """
                        SELECT recipient_name, street, house_number, apartment_number,
                               postal_code, city, country
                        FROM shop_user_addresses
                        WHERE user_id = ?
                        """,
                        (rs, rowNum) -> new AddressRow(
                                rs.getString("recipient_name"),
                                rs.getString("street"),
                                rs.getString("house_number"),
                                rs.getString("apartment_number"),
                                rs.getString("postal_code"),
                                rs.getString("city"),
                                rs.getString("country")
                        ),
                        userId
                )
                .stream()
                .findFirst();
    }

    public void upsertAddress(
            long userId,
            String recipientName,
            String street,
            String houseNumber,
            String apartmentNumber,
            String postalCode,
            String city,
            String country
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO shop_user_addresses (
                    user_id, recipient_name, street, house_number, apartment_number,
                    postal_code, city, country
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (user_id) DO UPDATE SET
                    recipient_name = EXCLUDED.recipient_name,
                    street = EXCLUDED.street,
                    house_number = EXCLUDED.house_number,
                    apartment_number = EXCLUDED.apartment_number,
                    postal_code = EXCLUDED.postal_code,
                    city = EXCLUDED.city,
                    country = EXCLUDED.country,
                    updated_at = CURRENT_TIMESTAMP
                """,
                userId, recipientName, street, houseNumber, apartmentNumber,
                postalCode, city, country
        );
    }

    public record ProfileRow(
            String email,
            String customerId,
            String firstName,
            String lastName,
            String phone
    ) {
    }

    public record AddressRow(
            String recipientName,
            String street,
            String houseNumber,
            String apartmentNumber,
            String postalCode,
            String city,
            String country
    ) {
    }
}
