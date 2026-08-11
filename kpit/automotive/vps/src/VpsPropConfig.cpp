#include "VpsPropConfig.h"

#include <algorithm>

namespace vps {

bool VpsPropConfig::supportsArea(int32_t areaId) const {
    return std::find(supportedAreas.begin(), supportedAreas.end(), areaId) != supportedAreas.end();
}

bool VpsPropConfig::isInRange(float value) const {
    if (maxValue <= minValue) {
        return true;  // unbounded (e.g. plain booleans, or a property with no declared range)
    }
    return value >= minValue && value <= maxValue;
}

}  // namespace vps
