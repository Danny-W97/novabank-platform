package org.demo.bankdemo.history;

import jakarta.persistence.criteria.JoinType;
import org.demo.bankdemo.transaction.BankTransaction;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;

final class HistorySpecifications {

    private HistorySpecifications() {
    }

    static Specification<BankTransaction> visibleTo(Long accountId, HistoryFilter filter) {
        return (root, query, builder) -> {
            var source = root.join("sourceAccount", JoinType.LEFT);
            var destination = root.join("destinationAccount", JoinType.INNER);

            var ownership = builder.or(
                    builder.equal(source.get("id"), accountId),
                    builder.equal(destination.get("id"), accountId)
            );
            var predicate = ownership;

            predicate = switch (filter.normalizedDirection()) {
                case INCOMING -> builder.and(
                        predicate,
                        builder.equal(destination.get("id"), accountId)
                );
                case OUTGOING -> builder.and(
                        predicate,
                        builder.equal(source.get("id"), accountId)
                );
                case ALL -> predicate;
            };

            if (filter.getType() != null) {
                predicate = builder.and(
                        predicate,
                        builder.equal(root.get("type"), filter.getType())
                );
            }
            if (filter.getCurrency() != null) {
                predicate = builder.and(
                        predicate,
                        builder.equal(root.get("currency"), filter.getCurrency().name())
                );
            }
            if (filter.getDateFrom() != null) {
                var from = filter.getDateFrom()
                        .atStartOfDay(HistoryService.WARSAW)
                        .toInstant();
                predicate = builder.and(
                        predicate,
                        builder.greaterThanOrEqualTo(root.get("createdAt"), from)
                );
            }
            if (filter.getDateTo() != null) {
                var toExclusive = filter.getDateTo()
                        .plusDays(1)
                        .atStartOfDay(HistoryService.WARSAW)
                        .toInstant();
                predicate = builder.and(
                        predicate,
                        builder.lessThan(root.get("createdAt"), toExclusive)
                );
            }
            if (filter.getMinAmount() != null) {
                predicate = builder.and(
                        predicate,
                        builder.greaterThanOrEqualTo(root.get("amount"), filter.getMinAmount())
                );
            }
            if (filter.getMaxAmount() != null) {
                predicate = builder.and(
                        predicate,
                        builder.lessThanOrEqualTo(root.get("amount"), filter.getMaxAmount())
                );
            }

            var search = filter.normalizedQuery();
            if (search != null) {
                var pattern = "%" + search + "%";
                predicate = builder.and(
                        predicate,
                        builder.or(
                                builder.like(builder.lower(root.get("title")), pattern),
                                builder.like(builder.lower(root.get("referenceNumber")), pattern),
                                builder.like(
                                        builder.lower(builder.coalesce(root.get("senderNameSnapshot"), "")),
                                        pattern
                                ),
                                builder.like(
                                        builder.lower(root.get("recipientNameSnapshot")),
                                        pattern
                                )
                        )
                );
            }
            return predicate;
        };
    }

    static Specification<BankTransaction> withId(Long transactionId) {
        return (root, query, builder) -> builder.equal(root.get("id"), transactionId);
    }
}
