package in.boss.masterdata.domain;

import in.boss.masterdata.api.dto.CreateUomRequest;
import in.boss.masterdata.api.dto.UomDto;

import java.util.List;
import java.util.UUID;

public interface UomService {
    List<UomDto> listUoms(String uomType);
    UomDto getUom(UUID id);
    UomDto createUom(CreateUomRequest request);
}
