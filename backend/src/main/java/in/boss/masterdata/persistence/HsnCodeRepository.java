package in.boss.masterdata.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface HsnCodeRepository extends JpaRepository<HsnCodeEntity, UUID> {
    Optional<HsnCodeEntity> findByCode(String code);
}
