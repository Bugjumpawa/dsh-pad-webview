# DSH Pad —— 手工构建 APK（aapt2 + javac + d8 + zipalign + apksigner）
# 刻意不用 Gradle：无网络依赖、无 wrapper、全流程可读可审计。
# 需要: JDK 17+、Android SDK (platforms;android-34 / build-tools;34.0.0)

SDK        ?= $(HOME)/Android/sdk
# 34.0.0 的 d8 对「匿名类访问外部私有成员」会 NPE（已实测），必须 >= 36
BT         ?= 36.0.0
API        ?= android-34
MIN_SDK    ?= 26
TARGET_SDK ?= 34

JAVAC      ?= javac
PYTHON     ?= python3
ADB        ?= $(HOME)/.local/bin/adb

JAR        := $(SDK)/platforms/$(API)/android.jar
AAPT2      := $(SDK)/build-tools/$(BT)/aapt2
D8         := $(SDK)/build-tools/$(BT)/d8
ZIPALIGN   := $(SDK)/build-tools/$(BT)/zipalign
APKSIGNER  := $(SDK)/build-tools/$(BT)/apksigner

KEYSTORE   ?= $(HOME)/.dsh/secrets/dsh-pad/keystore.jks
PASSFILE   ?= $(HOME)/.dsh/secrets/dsh-pad/pass
KEYALIAS   := dshpad

BUILD      := build
DIST       := dist
APK        := $(DIST)/dsh-pad.apk

SRC        := $(shell find src -name '*.java')
RES        := $(shell find res -type f)

.PHONY: all apk clean install badging sign-check keystore

all: $(APK)

# ---- 资源 ----
$(BUILD)/res.zip: $(RES) AndroidManifest.xml
	@mkdir -p $(BUILD)
	$(AAPT2) compile --dir res -o $@

$(BUILD)/base.apk: $(BUILD)/res.zip AndroidManifest.xml
	$(AAPT2) link -o $@ -I $(JAR) --manifest AndroidManifest.xml -R $(BUILD)/res.zip \
	  --auto-add-overlay --min-sdk-version $(MIN_SDK) --target-sdk-version $(TARGET_SDK)

# ---- Java -> dex ----
$(BUILD)/classes/.built: $(SRC)
	@mkdir -p $(BUILD)/classes
	$(JAVAC) -source 8 -target 8 -encoding UTF-8 -Xlint:-options \
	  -bootclasspath $(JAR) -d $(BUILD)/classes $(SRC)
	@touch $@

# 类名里的 $ 必须由内层 shell 展开（$$(...)）；用 $(shell ...) 会让外层 shell
# 把 MainActivity$1.class 变成 MainActivity.class，d8 报重复类。
$(BUILD)/dex/classes.dex: $(BUILD)/classes/.built
	@rm -rf $(BUILD)/dex && mkdir -p $(BUILD)/dex
	$(D8) --release --min-api $(MIN_SDK) --lib $(JAR) --output $(BUILD)/dex \
	  $$(find $(BUILD)/classes -name '*.class')

# ---- 打包 / 对齐 / 签名 ----
$(BUILD)/unaligned.apk: $(BUILD)/base.apk $(BUILD)/dex/classes.dex scripts/pack-apk.py
	$(PYTHON) scripts/pack-apk.py $(BUILD)/base.apk $(BUILD)/dex/classes.dex $@

$(BUILD)/aligned.apk: $(BUILD)/unaligned.apk
	$(ZIPALIGN) -f -p 4 $< $@

keystore:
	@mkdir -p $(dir $(KEYSTORE))
	@if [ ! -f $(PASSFILE) ]; then \
	  head -c 32 /dev/urandom | base64 | tr -d '/+=' | head -c 24 > $(PASSFILE); \
	  printf '\n' >> $(PASSFILE); cp $(PASSFILE) $(PASSFILE).tmp; cat $(PASSFILE) $(PASSFILE).tmp > $(PASSFILE).2; \
	  mv $(PASSFILE).2 $(PASSFILE); rm -f $(PASSFILE).tmp; chmod 600 $(PASSFILE); \
	  chmod 600 $(PASSFILE); echo "生成口令 -> $(PASSFILE)"; \
	fi
	@if [ ! -f $(KEYSTORE) ]; then \
	  keytool -genkeypair -keystore $(KEYSTORE) -alias $(KEYALIAS) -keyalg RSA -keysize 2048 \
	    -validity 10000 -storepass "$$(cat $(PASSFILE))" -keypass "$$(cat $(PASSFILE))" \
	    -dname "CN=DSH Pad, OU=personal, O=atheris, C=CN"; \
	fi

# keystore 用 order-only 依赖：目标已存在时不因它变“过期”，否则会无限递归重签
$(APK): $(BUILD)/aligned.apk | keystore
	@mkdir -p $(DIST)
	$(APKSIGNER) sign --ks $(KEYSTORE) --ks-key-alias $(KEYALIAS) \
	  --ks-pass file:$(PASSFILE) --key-pass file:$(PASSFILE) \
	  --v4-signing-enabled false --out $@ $<
	@$(MAKE) --no-print-directory sign-check

sign-check: $(APK)
	$(APKSIGNER) verify --print-certs $(APK) | head -4

badging: $(APK)
	$(AAPT2) dump badging $(APK) | head -12

install: $(APK)
	$(ADB) install -r $(APK)

clean:
	rm -rf $(BUILD) $(DIST)
