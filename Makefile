BASE_IMAGE ?= ceskaexpedice/curator-worker:7.2.2.5
IMAGE      ?= kramerius-mzk-worker
# Comma-separated plugins to build into the image, e.g. PLUGINS=ndk-import; empty means all
PLUGINS    ?=
TAG        ?= $(lastword $(subst :, ,$(BASE_IMAGE)))-$(shell git describe --always --dirty)

.PHONY: build test push

build:
	docker build --build-arg BASE_IMAGE=$(BASE_IMAGE) --build-arg PLUGINS=$(PLUGINS) -t $(IMAGE):$(TAG) .

# Unit tests run in the build stage
test:
	docker build --build-arg BASE_IMAGE=$(BASE_IMAGE) --build-arg PLUGINS=$(PLUGINS) --target build .

push: build
	@case "$(IMAGE)" in */*) ;; *) echo "Set IMAGE to a full registry path, e.g. make push IMAGE=registry.example.org/group/worker"; exit 1;; esac
	docker push $(IMAGE):$(TAG)
