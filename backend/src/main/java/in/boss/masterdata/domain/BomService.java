package in.boss.masterdata.domain;

import in.boss.masterdata.api.dto.BOMHeaderDto;
import in.boss.masterdata.api.dto.CreateBOMRequest;

import java.util.List;
import java.util.UUID;

public interface BomService {
    List<BOMHeaderDto> list();
    BOMHeaderDto getItemBOM(UUID itemId);
    BOMHeaderDto createOrReviseBOM(UUID itemId, CreateBOMRequest request);
}
