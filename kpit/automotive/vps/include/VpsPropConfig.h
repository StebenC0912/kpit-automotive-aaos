#pragma once

#include <cstdint>
#include <vector>

#include "IVpsHandler.h"

namespace vps {

// Per-property metadata, modeled on real VHAL's VehiclePropConfig (access mode, change mode,
// supported areas, value range) -- Stage 1 of moving vps/ closer to real VHAL's structure (see
// kpit/docs/03-implementation-status.md). A handler builds one VpsPropConfig per property it owns
// and validates every get/set against it instead of trusting propId/areaId/value blindly.
struct VpsPropConfig {
    enum class Access { READ, WRITE, READ_WRITE };
    enum class ChangeMode { ON_CHANGE, CONTINUOUS };

    int32_t propId = 0;
    VpsPropValue::Type type = VpsPropValue::Type::FLOAT;
    Access access = Access::READ_WRITE;
    ChangeMode changeMode = ChangeMode::ON_CHANGE;
    std::vector<int32_t> supportedAreas;  // e.g. {AREA_GLOBAL} or {DRIVER, PASSENGER}

    // minValue == maxValue means "not range-bounded" (e.g. plain booleans) -- only enforced when
    // maxValue > minValue.
    float minValue = 0.0f;
    float maxValue = 0.0f;

    bool supportsArea(int32_t areaId) const;
    bool isReadable() const { return access != Access::WRITE; }
    bool isWritable() const { return access != Access::READ; }
    bool isInRange(float value) const;
};

}  // namespace vps
