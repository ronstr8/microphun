.DEFAULT_GOAL := help
.PHONY: help build install run test test-kotlin test-native test-tsan lint clean ci

GRADLE  := ./gradlew
ADB     ?= $(HOME)/Android/Sdk/platform-tools/adb
APP_ID  := com.microphun
CPP_DIR := app/src/main/cpp
NATIVE_OUT := build/native-tests
CXX     ?= g++
# Each host test links against the one source file it covers.
NATIVE_TESTS := sample_ring speech_tap

help: ## Show this list
	@grep -hE '^[a-z-]+:.*## ' $(MAKEFILE_LIST) | \
		awk -F':.*## ' '{printf "  \033[36m%-12s\033[0m %s\n", $$1, $$2}'

build: ## Build the debug APK
	$(GRADLE) :app:assembleDebug

install: ## Install the debug APK on the connected device
	$(GRADLE) :app:installDebug

run: install ## Install and launch on the connected device
	$(ADB) shell am start -n $(APP_ID)/.MainActivity

test: test-kotlin test-native ## Run all host tests (Kotlin and native)

test-kotlin: ## Run the Kotlin unit tests
	$(GRADLE) :app:testDebugUnitTest

test-native: ## Build and run the native host tests
	@mkdir -p $(NATIVE_OUT)
	@set -e; for t in $(NATIVE_TESTS); do \
		echo "== $$t"; \
		$(CXX) -std=c++17 -O2 -I$(CPP_DIR) $(CPP_DIR)/$${t}_test.cpp $(CPP_DIR)/$$t.cpp \
			-o $(NATIVE_OUT)/$$t -pthread; \
		$(NATIVE_OUT)/$$t; \
	done

test-tsan: ## Run the native host tests under ThreadSanitizer
	@mkdir -p $(NATIVE_OUT)
	@set -e; for t in $(NATIVE_TESTS); do \
		echo "== $$t (tsan)"; \
		$(CXX) -std=c++17 -O1 -g -fsanitize=thread -I$(CPP_DIR) \
			$(CPP_DIR)/$${t}_test.cpp $(CPP_DIR)/$$t.cpp -o $(NATIVE_OUT)/$$t-tsan -pthread; \
		setarch $$(uname -m) -R $(NATIVE_OUT)/$$t-tsan; \
	done

lint: ## Run Android lint
	$(GRADLE) :app:lintDebug

clean: ## Remove build outputs
	$(GRADLE) clean
	rm -rf $(NATIVE_OUT)

ci: lint test build ## Everything that must pass before a commit
