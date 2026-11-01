package com.omkar.ledger.account;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data generates the implementation of this interface at startup (like a Django Manager
 * whose methods are derived from their names or from the JPQL in {@code @Query}).
 *
 * <p>The id/type lookups below return scalars, not entities, on purpose: they must not put an
 * Account into the persistence context before LedgerService locks it with SELECT ... FOR UPDATE,
 * otherwise the locked read could be served from a stale, unlocked copy.
 */
public interface AccountRepository extends JpaRepository<Account, UUID> {

    boolean existsByCode(String code);

    @Query("select a.id from Account a where a.code = :code")
    Optional<UUID> findIdByCode(@Param("code") String code);

    @Query("select a.type from Account a where a.id = :id")
    Optional<AccountType> findTypeById(@Param("id") UUID id);
}
