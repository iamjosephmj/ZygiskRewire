BUILD_DIR := $(CURDIR)/build

# 'debug' stays the default goal; the identity: rule below must not become one.
.DEFAULT_GOAL := debug

include common.mk

OBJ_DIR = $(BUILD_DIR)/obj/$(BUILD_TYPE)
MODULE_OUT = $(BUILD_DIR)/module/$(BUILD_TYPE)
ZIP_DIR = $(BUILD_DIR)/out

ZKSU_VERSION = $(VER_NAME)-$(VER_CODE)-$(COMMIT_HASH)-$(BUILD_TYPE)

ZIP_NAME = $(MODULE_NAME)-$(VER_NAME)-$(VER_CODE)-$(COMMIT_HASH)-$(BUILD_TYPE).zip
ZIP_FILE = $(ZIP_DIR)/$(ZIP_NAME)

ifeq ($(TERMUX_VERSION),)
	ADB_CMD := adb push $(ZIP_FILE) /data/local/tmp && adb shell 
	INSTALL_PATH := /data/local/tmp/$(ZIP_NAME)
	REBOOT_CMD := adb reboot
else
	INSTALL_PATH := $(ZIP_FILE)
	REBOOT_CMD := su -c reboot
endif

LOADER_DONE = $(OBJ_DIR)/loader/.done
ZYGISKD_DONE = $(OBJ_DIR)/zygiskd/.done
MODULE_DONE = $(BUILD_DIR)/module-$(BUILD_TYPE).done

LOADER_INPUTS = common.mk $(ROOT_DIR)/build/identity.mk loader/Makefile \
        $(shell find loader/src -type f | sort)

ZYGISKD_INPUTS = common.mk $(ROOT_DIR)/build/identity.mk zygiskd/Makefile \
        $(shell find zygiskd/src -type f | sort)

MODULE_INPUTS = scripts/sign.py \
        $(shell find module/src -type f | sort) \
        $(shell find webroot -type f | sort) \
        $(wildcard module/private_key module/public_key)

.PHONY: debug release build clean                                         \
        identity                                                          \
        installKsu installMagisk installAPatch                            \
        installKsuAndReboot installMagiskAndReboot installAPatchAndReboot \
        analyzer

identity:
	$(ROOT_DIR)/scripts/gen_identity.sh $(BUILD_DIR)

$(BUILD_DIR)/identity.mk: scripts/gen_identity.sh
	$(ROOT_DIR)/scripts/gen_identity.sh $(BUILD_DIR)

debug release build: $(BUILD_DIR)/identity.mk

debug:
	$(ROOT_DIR)/scripts/gen_identity.sh $(BUILD_DIR)
	$(MAKE) BUILD_TYPE=debug BUILD_DIR=$(BUILD_DIR) build

release:
	$(ROOT_DIR)/scripts/gen_identity.sh $(BUILD_DIR)
	$(MAKE) BUILD_TYPE=release BUILD_DIR=$(BUILD_DIR) build

all: debug release

build: $(ZIP_FILE)

$(LOADER_DONE): $(LOADER_INPUTS)
	$(MAKE) -C loader BUILD_TYPE=$(BUILD_TYPE) BUILD_DIR=$(BUILD_DIR) ZKSU_VERSION=$(ZKSU_VERSION)
	@mkdir -p $(dir $@)
	@touch $@

$(ZYGISKD_DONE): $(ZYGISKD_INPUTS)
	$(MAKE) -C zygiskd BUILD_TYPE=$(BUILD_TYPE) BUILD_DIR=$(BUILD_DIR) ZKSU_VERSION=$(ZKSU_VERSION)
	@mkdir -p $(dir $@)
	@touch $@

$(MODULE_DONE): $(LOADER_DONE) $(ZYGISKD_DONE) $(BUILD_DIR)/identity.mk $(MODULE_INPUTS)

	@rm -rf $(MODULE_OUT)
	@mkdir -p $(MODULE_OUT)/META-INF/com/google/android

	@echo "Copying META-INF files..."
	@cp module/src/META-INF/com/google/android/update-binary  \
	   module/src/META-INF/com/google/android/updater-script \
	   $(MODULE_OUT)/META-INF/com/google/android/


	@echo "Copying module files..."
	@cp module/src/verify.sh module/src/sepolicy.rule $(MODULE_OUT)/
	@cp module/src/rezygisk.sh $(MODULE_OUT)/$$(sed -n 's/^ID_MOD_ID := //p' $(BUILD_DIR)/identity.mk).sh

	@echo "Customizing module.prop..."
	@sed -e 's/$${versionName}/$(VER_NAME) ($(VER_CODE)-$(COMMIT_HASH)-$(BUILD_TYPE))/g' \
	    -e 's/$${versionCode}/$(VER_CODE)/g'                                            \
	    module/src/module.prop > $(MODULE_OUT)/module.prop

	@echo "Customizing scripts..."
	@for script in customize.sh post-fs-data.sh service.sh uninstall.sh; do \
		sed -e 's/@DEBUG@/$(if $(filter debug,$(BUILD_TYPE)),true,false)/g' \
		    -e 's/@MIN_APATCH_VERSION@/$(MIN_APATCH_VERSION)/g'             \
		    -e 's/@MIN_KSU_VERSION@/$(MIN_KSU_VERSION)/g'                   \
		    -e 's/@MIN_KSUD_VERSION@/$(MIN_KSUD_VERSION)/g'                 \
		    -e 's/@MIN_MAGISK_VERSION@/$(MIN_MAGISK_VERSION)/g'             \
		    module/src/$$script > $(MODULE_OUT)/$$script;                   \
	done

	@echo "Rendering module files from per-build identity..."
	@bash scripts/render_module.sh                                        \
		$(MODULE_OUT)/module.prop                                           \
		$(MODULE_OUT)/sepolicy.rule                                         \
		$(MODULE_OUT)/$$(sed -n 's/^ID_MOD_ID := //p' $(BUILD_DIR)/identity.mk).sh \
		$(MODULE_OUT)/customize.sh $(MODULE_OUT)/post-fs-data.sh            \
		$(MODULE_OUT)/service.sh $(MODULE_OUT)/uninstall.sh
	@cp module/identity.env $(MODULE_OUT)/identity.env

	@echo "Copying binaries..."
	@ID_DAEMON_BIN=$$(sed -n 's/^ID_DAEMON_BIN := //p' $(BUILD_DIR)/identity.mk);  \
		ID_PTRACER_BIN=$$(sed -n 's/^ID_PTRACER_BIN := //p' $(BUILD_DIR)/identity.mk); \
		ID_LIB_NAME=$$(sed -n 's/^ID_LIB_NAME := //p' $(BUILD_DIR)/identity.mk);       \
		for arch in $(ARCHS); do                                                       \
			case $$arch in arm64-v8a|x86_64) bits=64;; *) bits=32;; esac;                \
			mkdir -p $(MODULE_OUT)/bin/$$arch $(MODULE_OUT)/lib/$$arch;                  \
			cp $(OBJ_DIR)/zygiskd/$$arch/zygiskd $(MODULE_OUT)/bin/$$arch/$${ID_DAEMON_BIN}$${bits};                  \
			cp $(OBJ_DIR)/loader/$$arch/stripped/libzygisk.so $(MODULE_OUT)/lib/$$arch/$${ID_LIB_NAME};               \
			cp $(OBJ_DIR)/loader/$$arch/stripped/libzygisk_ptrace.so $(MODULE_OUT)/bin/$$arch/$${ID_PTRACER_BIN}$${bits}; \
		done

	@echo "Copying webroot..."
	@cp -r webroot $(MODULE_OUT)/webroot
	@bash scripts/render_module.sh $$(grep -rl -- '@@' $(MODULE_OUT)/webroot 2>/dev/null || true)

	@if [ -f module/private_key ]; then                                                  \
		echo "Signing module...";                                                        \
		ID_MOD_ID=$$(sed -n 's/^ID_MOD_ID := //p' $(BUILD_DIR)/identity.mk)              \
		ID_LIB_NAME=$$(sed -n 's/^ID_LIB_NAME := //p' $(BUILD_DIR)/identity.mk)          \
		ID_DAEMON_BIN=$$(sed -n 's/^ID_DAEMON_BIN := //p' $(BUILD_DIR)/identity.mk)      \
		ID_PTRACER_BIN=$$(sed -n 's/^ID_PTRACER_BIN := //p' $(BUILD_DIR)/identity.mk)    \
		python3 scripts/sign.py $(MODULE_OUT) module/private_key module/public_key;      \
	else                                                                                 \
	    echo "No private key found, skipping signing...";                                \
		python3 scripts/sign.py --no-sign $(MODULE_OUT);                                 \
	fi

	@mkdir -p $(dir $@)
	@touch $@

$(ZIP_FILE): $(MODULE_DONE)
	@mkdir -p $(ZIP_DIR)
	@rm -f $@

	@echo "Creating ZIP file..."
	@cd $(MODULE_OUT) && zip -r9 $@ . -x '*.DS_Store' > /dev/null

installKsu: build
	$(ADB_CMD)su -c '/data/adb/ksu/bin/ksud module install $(INSTALL_PATH)'

installMagisk: build
	$(ADB_CMD)su -M -c "magisk --install-module $(INSTALL_PATH)"

installAPatch: build
	$(ADB_CMD)su -c "/data/adb/apd module install $(INSTALL_PATH)"

installKsuAndReboot: installKsu
	$(REBOOT_CMD)

installMagiskAndReboot: installMagisk
	$(REBOOT_CMD)

installAPatchAndReboot: installAPatch
	$(REBOOT_CMD)

analyzer:
	$(MAKE) -C loader clean BUILD_TYPE=debug BUILD_DIR=$(BUILD_DIR)
	$(MAKE) -C zygiskd clean BUILD_TYPE=debug BUILD_DIR=$(BUILD_DIR)
	@rm -f $(BUILD_DIR)/module-debug.done $(BUILD_DIR)/obj/debug/loader/.done $(BUILD_DIR)/obj/debug/zygiskd/.done
	@$(MAKE) -k BUILD_TYPE=debug ARCH=arm64-v8a ARCHS="arm64-v8a" build \
		CC='$(TOOLCHAIN)/bin/clang --analyze -Wno-unused-command-line-argument -Xanalyzer -analyzer-output=text'

clean:
	rm -rf $(BUILD_DIR)
	$(MAKE) -C loader clean BUILD_DIR=$(BUILD_DIR)
	$(MAKE) -C zygiskd clean BUILD_DIR=$(BUILD_DIR)
