package in.boss.masterdata.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ItemCategoryRepository extends JpaRepository<ItemCategoryEntity, UUID> {
    List<ItemCategoryEntity> findByTenantId(UUID tenantId);
    List<ItemCategoryEntity> findByTenantIdAndIsActiveTrue(UUID tenantId);
    Optional<ItemCategoryEntity> findByIdAndTenantId(UUID id, UUID tenantId);
    Optional<ItemCategoryEntity> findByTenantIdAndCode(UUID tenantId, String code);
    boolean existsByTenantIdAndCode(UUID tenantId, String code);
    boolean existsByTenantIdAndParentIdAndIsActiveTrue(UUID tenantId, UUID parentId);
}
