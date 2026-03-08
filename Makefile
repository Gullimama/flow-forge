.PHONY: build test lint docker

build:              ## Build all modules
	./gradlew build -x test

test:               ## Run all tests
	./gradlew test

test-unit:          ## Run unit tests only
	./gradlew test --tests '*Unit*'

test-integration:   ## Run integration tests (requires Docker)
	./gradlew integrationTest

verify:             ## Verify all implemented stages (1..N): clean build + integration tests + lint
	./gradlew clean build integrationTest --rerun-tasks
	$(MAKE) lint

verify-stages-1-5:  ## Alias for verify (ensures stages 1–5 pass; same as verify while only 5 stages exist)
	$(MAKE) verify

lint:               ## Run checkstyle (spotbugs skipped: not yet compatible with Java 25)
	./gradlew checkstyleMain

docker:             ## Build all Docker images
	./gradlew bootBuildImage

k8s-validate:       ## Validate all K8s manifests
	kubectl kustomize k8s/ml-serving/tei-code/ --enable-helm | kubectl apply --dry-run=client -f -
	kubectl kustomize k8s/ml-serving/vllm/ --enable-helm | kubectl apply --dry-run=client -f -
	kubectl kustomize k8s/app/flowforge-api/ --enable-helm | kubectl apply --dry-run=client -f -

argocd-diff:        ## Show ArgoCD diff for all apps
	argocd app diff flowforge-root --local k8s/argocd/

dev-up:             ## Start local dev infrastructure
	docker compose -f docker/docker-compose.yml up -d

dev-down:           ## Stop local dev infrastructure
	docker compose -f docker/docker-compose.yml down

help:               ## Show this help
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | sort | awk 'BEGIN {FS = ":.*?## "}; {printf "\033[36m%-20s\033[0m %s\n", $$1, $$2}'
