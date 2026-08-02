#
# vendor/kpit/automotive/products/kpit_apps.mk
#
# Pulls the KPIT HMI apps and their backing system services into the product image.
# Inherited from the device product makefile(s) actually used by the target lunch
# combo(s) - see aosp_car_x86_64.mk / sdk_car_x86_64.mk in device/generic/car/.
#
# HVAC (Comfort) and Bluetooth (Connectivity) domains only - Seat and WiFi are not
# yet implemented (vendor/kpit/instruction.md section III) and have no buildable
# modules to add here.
#

PRODUCT_PACKAGES += \
    hvac-app \
    hvac-service \
    bluetooth-app \
    bluetooth-service \
    libvps \
    libbase_comfort_jni \
    privapp_permissions_bluetooth.xml

#
# sdk_car_x86_64.mk inherits packages/services/Car/car_product/build/car_generic_system.mk,
# which declares a strict artifact path requirement over TARGET_COPY_OUT_ROOT/SYSTEM (GSI
# compliance - only files car_generic_system.mk itself lists may land in system.img). These
# four modules install to system/priv-app, so they must be explicitly allowlisted here or the
# build fails with "produces files inside ... artifact path requirement".
#
#
# libvps/libbase_comfort_jni were originally vendor:true (installed to /vendor/lib64,
# outside this requirement's ROOT/SYSTEM scope) but were later moved to /system (lib +
# lib64, 32- and 64-bit) per instruction.md section X's SEPolicy write-up - coredomain
# system_app processes can't load /vendor libraries under Full-Treble neverallow rules.
# That partition move puts them under TARGET_COPY_OUT_SYSTEM too, so they need the same
# allowlisting as the priv-app packages below.
#
PRODUCT_ARTIFACT_PATH_REQUIREMENT_ALLOWED_LIST += \
    system/etc/permissions/privapp_permissions_bluetooth.xml \
    system/priv-app/bluetooth-app/% \
    system/priv-app/bluetooth-service/% \
    system/priv-app/hvac-app/% \
    system/priv-app/hvac-service/% \
    system/lib/libbase_comfort_jni.so \
    system/lib/libvps.so \
    system/lib64/libbase_comfort_jni.so \
    system/lib64/libvps.so
