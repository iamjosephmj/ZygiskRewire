# Hello World Zygisk module — builds against the ZygiskRewire loader ABI.
#
#   make            build both ABIs + zip into out/
#   make clean
#
# Requires: ANDROID_HOME (NDK), build-tools d8, javac, python3 (dex symbol rename).

NDK_PROJECT := $(HOME)/Android/Sdk/ndk/29.0.14206865
TOOLCHAIN   := $(NDK_PROJECT)/toolchains/llvm/prebuilt/linux-x86_64/bin
D8          := $(lastword $(sort $(wildcard $(HOME)/Android/Sdk/build-tools/*/d8)))
JAVAC       := javac

OUT         := out
ZIP         := $(OUT)/helloworld-module.zip

ABIS        := arm64-v8a armeabi-v7a
TRIPLE_arm64-v8a   := aarch64-linux-android24
TRIPLE_armeabi-v7a := armv7a-linux-androideabi24

CXXFLAGS    := -std=c++17 -O2 -fvisibility=hidden -fno-exceptions -fno-rtti \
               -Wall -Wextra -static-libstdc++ -nostdlib++ \
               -Wl,-z,max-page-size=4096

.PHONY: all clean zip
all: $(ZIP)

# --- embedded dex: java -> class -> dex -> binary object ---
ANDROID_JAR := $(lastword $(sort $(wildcard $(HOME)/Android/Sdk/platforms/android-*/android.jar)))

$(OUT)/classes/com/zygiskrewire/hello/HelloWorldHook.class: java/com/zygiskrewire/hello/HelloWorldHook.java
	@mkdir -p $(OUT)/classes
	$(JAVAC) --release 11 -classpath $(lastword $(ANDROID_JAR)) -d $(OUT)/classes $<

# Pass all nest-mate classes via a SHELL glob: a make $(wildcard) here would
# let make eat the $1/$2 in the inner-class filenames as variable references.
$(OUT)/classes.dex: $(OUT)/classes/com/zygiskrewire/hello/HelloWorldHook.class
	$(D8) --release --min-api 26 --output $(OUT) \
		$(OUT)/classes/com/zygiskrewire/hello/*.class

$(OUT)/payload.dex: $(OUT)/classes.dex
	cp $< $@

# objcopy names binary symbols after the input file (payload.dex ->
# _binary_payload_dex_start/_end); redefine them to the names the .cpp expects.
$(OUT)/dex_embed_arm64.o: $(OUT)/payload.dex
	cd $(OUT) && $(TOOLCHAIN)/llvm-objcopy -I binary -O elf64-littleaarch64 -B aarch64 \
		--redefine-sym _binary_payload_dex_start=hello_dex_start \
		--redefine-sym _binary_payload_dex_end=hello_dex_end payload.dex dex_embed_arm64.o

$(OUT)/dex_embed_arm.o: $(OUT)/payload.dex
	cd $(OUT) && $(TOOLCHAIN)/llvm-objcopy -I binary -O elf32-littlearm -B arm \
		--redefine-sym _binary_payload_dex_start=hello_dex_start \
		--redefine-sym _binary_payload_dex_end=hello_dex_end payload.dex dex_embed_arm.o

# --- native modules ---

# fix: emit explicit per-ABI rules instead of the foreach above (kept simple)
$(OUT)/zygisk/arm64-v8a.so: jni/hello_module.cpp $(OUT)/dex_embed_arm64.o
	@mkdir -p $(OUT)/zygisk
	$(TOOLCHAIN)/aarch64-linux-android24-clang++ $(CXXFLAGS) -shared -o $@ \
		jni/hello_module.cpp $(OUT)/dex_embed_arm64.o -llog

$(OUT)/zygisk/armeabi-v7a.so: jni/hello_module.cpp $(OUT)/dex_embed_arm.o
	@mkdir -p $(OUT)/zygisk
	$(TOOLCHAIN)/armv7a-linux-androideabi24-clang++ $(CXXFLAGS) -shared -o $@ \
		jni/hello_module.cpp $(OUT)/dex_embed_arm.o -llog

# --- zip ---
$(ZIP): $(OUT)/zygisk/arm64-v8a.so $(OUT)/zygisk/armeabi-v7a.so module.prop
	@mkdir -p $(OUT)
	cp module.prop $(OUT)/module.prop
	cd $(OUT) && zip -q -r ../$(ZIP) module.prop zygisk


clean:
	rm -rf $(OUT)
