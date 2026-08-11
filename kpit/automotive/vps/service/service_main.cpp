/*
 * service_main.cpp
 *
 * Entry point for vendor.kpit.vps-service (VHAL-alignment Stage 4,
 * kpit/docs/03-implementation-status.md item 13). Registers VpsServiceImpl under
 * vendor.kpit.vps.IVpsService/default and joins the Binder thread pool -- modeled directly on
 * hardware/interfaces/automotive/can/aidl/default/service.cpp's main().
 */

#include <android-base/logging.h>
#include <android/binder_manager.h>
#include <android/binder_process.h>

#include <string>

#include "VpsServiceImpl.h"

using aidl::vendor::kpit::vps::IVpsService;

int main() {
    android::base::SetDefaultTag("VpsService");
    android::base::SetMinimumLogSeverity(android::base::VERBOSE);

    ABinderProcess_setThreadPoolMaxThreadCount(4);

    auto service = ndk::SharedRefBase::make<vps::service::VpsServiceImpl>();
    const std::string instance = std::string(IVpsService::descriptor) + "/default";
    binder_status_t status = AServiceManager_addService(service->asBinder().get(), instance.c_str());
    CHECK_EQ(status, STATUS_OK) << "Failed to register " << instance;

    ABinderProcess_startThreadPool();
    ABinderProcess_joinThreadPool();
    LOG(FATAL) << "vendor.kpit.vps-service exited unexpectedly";
    return EXIT_FAILURE;
}
