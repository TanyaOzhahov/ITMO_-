// jobs.groovy
// Job DSL script: defines all Adapstory Jenkins Pipeline jobs as code.
//
// Executed by the 'seed' Pipeline job (infra/ci/jenkins/pipelines/seed.jenkinsfile).
// Seed is triggered automatically via GitHub webhook on every push to Adapstory-GitOps.
//
// To add a new job: add a pipelineJob() block here and push to main.
// To remove a job: delete its block. Seed will DISABLE (not delete) the job.
//
// All jobs share the same SCM: Adapstory-GitOps repo, branch */main.
// Credential IDs are managed via ESO + Vault → kubernetes-credentials-provider.

// ──────────────────────────────────────────────────────────────────────────────
// Helper closure: common SCM definition pointing to Adapstory-GitOps
// ──────────────────────────────────────────────────────────────────────────────
def gitopsScm(String jenkinsfilePath) {
    return {
        cpsScm {
            scm {
                git {
                    remote {
                        url('https://github.com/adapstory/Adapstory-GitOps.git')
                        credentials('github-token')
                    }
                    branch('*/main')
                    extensions {
                        cloneOptions {
                            shallow(true)
                            depth(1)
                        }
                    }
                }
            }
            scriptPath(jenkinsfilePath)
            lightweight(true)
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// 0. jenkins-shared-lib-test — Unit tests for adapstory-jenkins-shared-lib (JenkinsPipelineUnit)
// ──────────────────────────────────────────────────────────────────────────────
pipelineJob('jenkins-shared-lib-test') {
    description('Run JenkinsPipelineUnit unit tests for adapstory-jenkins-shared-lib. Gradle + JUnit 5. No Docker/dind required.')
    parameters {
        stringParam('GIT_BRANCH', 'main', 'Branch of adapstory-jenkins-shared-lib to test')
    }
    definition {
        cpsScm {
            scm {
                git {
                    remote {
                        url('https://github.com/adapstory/adapstory-jenkins-shared-lib.git')
                        credentials('github-token')
                    }
                    branch('*/${GIT_BRANCH}')
                    extensions {
                        cloneOptions {
                            shallow(true)
                            depth(1)
                        }
                    }
                }
            }
            scriptPath('test/Jenkinsfile')
            lightweight(true)
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// 1. deploy-dev — Unified Java microservice builder (Maven → Docker → Harbor → dev)
// ──────────────────────────────────────────────────────────────────────────────
pipelineJob('deploy-dev') {
    description('Build and deploy Java microservice to dev. Maven 3-phase → Kaniko → Harbor → GitOps update → ArgoCD sync.')
    parameters {
        choiceParam('SERVICE', [
            'adapstory-bff-admin',
            'adapstory-bff-student',
            'adapstory-identity',
            'adapstory-data-model-engine',
            'adapstory-multi-tenant-runtime',
            'adapstory-plugin-lifecycle',
            'adapstory-plugin-gateway',
            'adapstory-content-repository',
            'adapstory-personalization-runtime'
        ], 'Service to build and deploy')
        stringParam('TAG', '', 'Image tag (empty = auto YY.MM.DD-shortSHA)')
        stringParam('GIT_BRANCH', 'main', 'Branch to build from')
        booleanParam('SKIP_TESTS', false, 'Skip Maven tests (hotfixes only)')
        booleanParam('SKIP_CHECKS', false, 'Skip Checkstyle/PMD/SpotBugs (formatting-only commits)')
        booleanParam('PUSH_GITHUB_PACKAGES', false, 'Also push Maven artifacts to GitHub Packages (mirror)')
        booleanParam('PUSH_GHCR', false, 'Also push Docker image to GHCR (mirror)')
    }
    definition(gitopsScm('infra/ci/jenkins/pipelines/deploy-dev.jenkinsfile'))
}

// ──────────────────────────────────────────────────────────────────────────────
// 1b. deploy-dev-v2 — Same as deploy-dev + "Generate OpenAPI Docs" stage
// ──────────────────────────────────────────────────────────────────────────────
pipelineJob('deploy-dev-v2') {
    description('deploy-dev v2: Maven → Kaniko → Harbor → GitOps → OpenAPI docs commit to service repo → DAST. Docs failure is non-blocking (UNSTABLE).')
    parameters {
        choiceParam('SERVICE', [
            'adapstory-bff-admin',
            'adapstory-bff-student',
            'adapstory-identity',
            'adapstory-data-model-engine',
            'adapstory-multi-tenant-runtime',
            'adapstory-plugin-lifecycle',
            'adapstory-plugin-gateway',
            'adapstory-content-repository',
            'adapstory-personalization-runtime'
        ], 'Service to build and deploy')
        stringParam('TAG', '', 'Image tag (empty = auto YY.MM.DD-shortSHA)')
        stringParam('GIT_BRANCH', 'main', 'Branch to build from')
        booleanParam('SKIP_TESTS', false, 'Skip Maven tests (hotfixes only)')
        booleanParam('SKIP_CHECKS', false, 'Skip Checkstyle/PMD/SpotBugs (formatting-only commits)')
        booleanParam('PUSH_GITHUB_PACKAGES', false, 'Also push Maven artifacts to GitHub Packages (mirror)')
        booleanParam('PUSH_GHCR', false, 'Also push Docker image to GHCR (mirror)')
        booleanParam('SKIP_DOCS', false, 'Skip OpenAPI docs generation (use for hotfixes)')
    }
    definition(gitopsScm('infra/ci/jenkins/pipelines/deploy-dev-v3.jenkinsfile'))
    // Was: deploy-dev-v2.jenkinsfile. Rollback: change back to v2.
}

// ──────────────────────────────────────────────────────────────────────────────
// 1c. deploy-dev-v3 — Thin wrapper: all logic in adapstory-jenkins-shared-lib.
//     Includes: captureDigest, slsaProvenance, SBOM attestation, cosignSign, mvnw, OpenAPI docs.
//     Rollback: change scriptPath to deploy-dev-v2.jenkinsfile.
// ──────────────────────────────────────────────────────────────────────────────
pipelineJob('deploy-dev-v3') {
    description('deploy-dev v3 (shared-lib): Build → Test → Docker → Trivy → Cosign → SBOM Attestation → SLSA Provenance → GitOps → OpenAPI Docs → DAST. All logic in adapstory-jenkins-shared-lib.')
    parameters {
        choiceParam('SERVICE', [
            'adapstory-bff-admin',
            'adapstory-bff-school',
            'adapstory-bff-student',
            'adapstory-identity',
            'adapstory-data-model-engine',
            'adapstory-multi-tenant-runtime',
            'adapstory-plugin-lifecycle',
            'adapstory-plugin-gateway',
            'adapstory-content-repository',
            'adapstory-personalization-runtime'
        ], 'Service to build and deploy')
        stringParam('TAG', '', 'Image tag (empty = auto YY.MM.DD-shortSHA)')
        stringParam('GIT_BRANCH', 'main', 'Branch to build from')
        choiceParam('DEPLOY_ENV', ['dev', 'prod'], 'Target environment for GitOps values')
        booleanParam('SKIP_TESTS', false, 'Skip Maven tests (hotfixes only)')
        booleanParam('SKIP_CHECKS', false, 'Skip Checkstyle/PMD/SpotBugs')
        booleanParam('PUSH_GITHUB_PACKAGES', false, 'Also push Maven artifacts to GitHub Packages (mirror)')
        booleanParam('PUSH_GHCR', false, 'Also push Docker image to GHCR (mirror)')
        booleanParam('SKIP_DOCS', false, 'Skip OpenAPI docs generation (use for hotfixes)')
    }
    definition(gitopsScm('infra/ci/jenkins/pipelines/deploy-dev-v3.jenkinsfile'))
}

// ──────────────────────────────────────────────────────────────────────────────
// 1d. retired aliases — legacy API-created names kept only as disabled GitOps stubs
// ──────────────────────────────────────────────────────────────────────────────
pipelineJob('adapstory-shared-libs') {
    description("RETIRED legacy alias. Disabled by GitOps. Use 'shared-libs-deploy' instead.")
    disabled(true)
    definition {
        cps {
            script(
                """
                pipeline {
                    agent none
                    stages {
                        stage('Retired Alias') {
                            steps {
                                error("Job 'adapstory-shared-libs' is retired. Use 'shared-libs-deploy' instead.")
                            }
                        }
                    }
                }
                """.stripIndent()
            )
            sandbox()
        }
    }
}

pipelineJob('adapstory-master-pom') {
    description("RETIRED legacy alias. Disabled by GitOps. Use 'master-pom-deploy' instead.")
    disabled(true)
    definition {
        cps {
            script(
                """
                pipeline {
                    agent none
                    stages {
                        stage('Retired Alias') {
                            steps {
                                error("Job 'adapstory-master-pom' is retired. Use 'master-pom-deploy' instead.")
                            }
                        }
                    }
                }
                """.stripIndent()
            )
            sandbox()
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// 2. shared-libs-deploy — Deploy adapstory-shared-libs to Nexus + GitHub Packages
// ──────────────────────────────────────────────────────────────────────────────
pipelineJob('shared-libs-deploy') {
    description('Build and deploy adapstory-shared-libs (Java foundation) to Nexus and GitHub Packages. Includes Testcontainers integration tests.')
    parameters {
        stringParam('GIT_BRANCH', 'main', 'Branch to build from')
        booleanParam('SKIP_TESTS', false, 'Skip Maven tests (hotfixes only)')
    }
    definition(gitopsScm('infra/ci/jenkins/pipelines/shared-libs-deploy.jenkinsfile'))
}

// ──────────────────────────────────────────────────────────────────────────────
// 3. master-pom-deploy — Deploy adapstory-master-pom BOM to Nexus + GitHub Packages
// ──────────────────────────────────────────────────────────────────────────────
pipelineJob('master-pom-deploy') {
    description('Deploy adapstory-master-pom (Bill of Materials) to Nexus and GitHub Packages. Run before shared-libs when BOM versions change.')
    parameters {
        stringParam('GIT_BRANCH', 'main', 'Branch to build from')
    }
    definition(gitopsScm('infra/ci/jenkins/pipelines/master-pom-deploy.jenkinsfile'))
}

// ──────────────────────────────────────────────────────────────────────────────
// 4. frontend-build — Next.js frontend (student / admin apps)
// ──────────────────────────────────────────────────────────────────────────────
pipelineJob('frontend-build') {
    description('Build Next.js frontend app (student/admin/school) → Kaniko → Harbor → GitOps deploy. BFF URLs are baked into the static build.')
    parameters {
        choiceParam('APP_NAME', ['student', 'admin', 'school'], 'Which Next.js app to build (maps to packages/<APP_NAME>)')
        stringParam('TAG', '', 'Docker image tag (auto-generated YY.MM.DD-shortSHA if empty)')
        stringParam('GIT_BRANCH', 'main', 'Branch to build from')
        choiceParam('DEPLOY_ENV', ['dev', 'prod'], 'Target environment for GitOps values + BFF URLs')
        booleanParam('SKIP_LINT', false, 'Skip ESLint (formatting-only commits)')
        stringParam('BFF_URL', '', 'BFF endpoint for Next.js rewrites (auto-derived from APP_NAME if empty)')
        stringParam('BFF_SCHOOL_URL', '', 'School BFF endpoint for school portal rewrites (auto-derived for APP_NAME=school if empty)')
    }
    definition(gitopsScm('infra/ci/jenkins/pipelines/frontend-build.jenkinsfile'))
}

// ──────────────────────────────────────────────────────────────────────────────
// 5. landing-build — Next.js landing page (SSG)
// ──────────────────────────────────────────────────────────────────────────────
pipelineJob('landing-build') {
    description('Build Next.js landing page (SSG) → Kaniko → Harbor → GitOps deploy. Includes DAST via OWASP ZAP.')
    parameters {
        stringParam('TAG', '', 'Docker image tag (auto-generated YY.MM.DD-shortSHA if empty)')
        stringParam('GIT_BRANCH', 'main', 'Branch to build from')
        choiceParam('DEPLOY_ENV', ['dev', 'prod'], 'Target environment for GitOps values + baked-in public URLs')
        booleanParam('SKIP_LINT', false, 'Skip ESLint (formatting-only commits)')
    }
    definition(gitopsScm('infra/ci/jenkins/pipelines/landing-build.jenkinsfile'))
}

// ──────────────────────────────────────────────────────────────────────────────
// 6. keycloak-build — Custom Keycloak with SPI extensions + Keycloakify theme
// ──────────────────────────────────────────────────────────────────────────────
pipelineJob('keycloak-build') {
    description('Build custom Keycloak image with SPI extensions (telegram, sms) and Keycloakify theme. Prebuilt mode (fast ~5min) or source mode (full Quarkus ~30min).')
    parameters {
        stringParam('TAG', '', 'Docker image tag (auto-generated if empty)')
        stringParam('GIT_BRANCH', 'main', 'adapstory-ai-lms branch')
        booleanParam('BUILD_FROM_SOURCE', false, 'Build Keycloak from source (slow, 30min). Default: use official base image (fast, 5min)')
    }
    definition(gitopsScm('infra/ci/jenkins/pipelines/keycloak-build.jenkinsfile'))
}

// ──────────────────────────────────────────────────────────────────────────────
// 7. appflowy-build — 5 AppFlowy Docker images (monorepo)
// ──────────────────────────────────────────────────────────────────────────────
pipelineJob('appflowy-build') {
    description('Build AppFlowy Docker images: gotrue, cloud, admin, worker, web. Parallel (light) + sequential (heavy Rust). Each image uses a separate Kaniko container.')
    parameters {
        choiceParam('IMAGE', [
            'all',
            'appflowy-gotrue',
            'appflowy-cloud',
            'appflowy-admin',
            'appflowy-worker',
            'appflowy-web'
        ], 'Which image(s) to build')
        stringParam('TAG', '', 'Image tag (auto-generated YY.MM.DD-COMMIT if empty)')
        stringParam('GIT_BRANCH', 'develop', 'Branch (default: develop)')
    }
    definition(gitopsScm('infra/ci/jenkins/pipelines/appflowy-build.jenkinsfile'))
}

// ──────────────────────────────────────────────────────────────────────────────
// 8. app-build — Generic builder for external applications
// ──────────────────────────────────────────────────────────────────────────────
pipelineJob('app-build') {
    description('Generic Kaniko builder for platform apps and external services (AI services, nocobase, tg tools, rag-service, whisper, etc). Includes Trivy scan, Cosign signing, and SBOM.')
    parameters {
        choiceParam('APP', [
            'rag-service-cuda',
            'rag-service-cpu',
            'whisper-webui',
            'nocobase',
            'frontend',
            'sgr-service',
            'tg-hw-checker',
            'tg-scheduled-bot',
            'deeptutor',
            'gliner-guard-litserve',
            'gliner-guard-ray-serve',
            'n8n-custom',
            'ai-course-generator',
            'dify-plugin',
            'edu-knowledge-graph'
        ], 'Application to build and deploy')
        stringParam('TAG', '', 'Image tag (empty = auto YY.MM.DD-shortSHA)')
        stringParam('GIT_BRANCH', 'main', 'Branch to build from')
    }
    definition(gitopsScm('infra/ci/jenkins/pipelines/app-build.jenkinsfile'))
}

// ──────────────────────────────────────────────────────────────────────────────
// 9. infra-build — Infrastructure custom Docker images
// ──────────────────────────────────────────────────────────────────────────────
pipelineJob('infra-build') {
    description('Build custom infrastructure images (kafka, postgres-pgvector, redis, minio, mysql, db-backup-tools, airflow, ci-git) → Harbor.')
    parameters {
        choiceParam('SERVICE', [
            'kafka',
            'postgres-pgvector',
            'redis',
            'minio',
            'mysql',
            'db-backup-tools',
            'airflow',
            'ci-git'
        ], 'Infrastructure service to build')
        stringParam('TAG', '', 'Image tag (leave empty for default version)')
    }
    definition(gitopsScm('infra/ci/jenkins/pipelines/infra-build.jenkinsfile'))
}

// ──────────────────────────────────────────────────────────────────────────────
// 10. infra-mirror — Mirror external images to Harbor (using crane, no build)
// ──────────────────────────────────────────────────────────────────────────────
pipelineJob('infra-mirror') {
    description('Mirror external Docker images to harbor.adapstory.com/adapstory/ using crane (no Docker daemon). Use to seed Harbor cache for offline builds.')
    parameters {
        choiceParam('IMAGE', [
            'elasticsearch',
            'opensearch',
            'weaviate',
            'neo4j',
            'cp-schema-registry',
            'cp-kafka-connect',
            'kafka-ui'
        ], 'External image to mirror')
        stringParam('TAG', '', 'Source tag (leave empty for default)')
    }
    definition(gitopsScm('infra/ci/jenkins/pipelines/infra-mirror.jenkinsfile'))
}

// ──────────────────────────────────────────────────────────────────────────────
// 11. tg-miniapp-build — Telegram Mini App (static HTML/JS/CSS)
// ──────────────────────────────────────────────────────────────────────────────
pipelineJob('tg-miniapp-build') {
    description('Build Telegram Mini App static content → Kaniko → Harbor → GitOps dev deploy.')
    parameters {
        stringParam('TAG', '', 'Docker image tag (auto-generated YY.MM.DD-shortSHA if empty)')
        stringParam('GIT_BRANCH', 'main', 'Branch to build from')
    }
    definition(gitopsScm('infra/ci/jenkins/pipelines/tg-miniapp-build.jenkinsfile'))
}

// ──────────────────────────────────────────────────────────────────────────────
// 12. bff-admin-build — BFF Admin Gateway (Java/Spring Boot)
// ──────────────────────────────────────────────────────────────────────────────
pipelineJob('bff-admin-build') {
    description('Build adapstory-bff-admin (Java/Spring Boot BFF) → Maven 3-phase → Kaniko → Harbor → GitOps dev deploy.')
    parameters {
        stringParam('TAG', '', 'Docker image tag (auto-generated from pom.xml version if empty)')
        stringParam('GIT_BRANCH', 'main', 'Branch to build from')
        booleanParam('SKIP_TESTS', false, 'Skip Maven tests (use only for hotfixes)')
    }
    definition(gitopsScm('infra/ci/jenkins/pipelines/bff-admin-build.jenkinsfile'))
}

// ──────────────────────────────────────────────────────────────────────────────
// 12b. bff-school-build — BFF School Gateway (Java/Spring Boot)
// ──────────────────────────────────────────────────────────────────────────────
pipelineJob('bff-school-build') {
    description('Build adapstory-bff-school (Java/Spring Boot BFF) → Maven 3-phase → Kaniko → Harbor → GitOps dev deploy.')
    parameters {
        stringParam('TAG', '', 'Docker image tag (auto-generated from pom.xml version if empty)')
        stringParam('GIT_BRANCH', 'main', 'Branch to build from')
        booleanParam('SKIP_TESTS', false, 'Skip Maven tests (use only for hotfixes)')
    }
    definition(gitopsScm('infra/ci/jenkins/pipelines/bff-school-build.jenkinsfile'))
}

// ──────────────────────────────────────────────────────────────────────────────
// 13. qodana-jvm-community-scan — Non-blocking JVM static analysis / audit job
// ──────────────────────────────────────────────────────────────────────────────
pipelineJob('qodana-jvm-community-scan') {
    description('Run Qodana Community for JVM against a selected GitHub repository/branch. Intended for optimization audits and code-quality discovery outside the deploy critical path.')
    parameters {
        stringParam('REPO_URL', 'https://github.com/adapstory/adapstory-jenkins-shared-lib.git', 'GitHub repository URL to scan (https://github.com/<org>/<repo>.git)')
        stringParam('GIT_BRANCH', 'main', 'Branch to scan')
        booleanParam('CLEAR_CACHE', false, 'Clear the persistent Qodana cache before scanning')
    }
    definition(gitopsScm('infra/ci/jenkins/pipelines/qodana-jvm-community-scan.jenkinsfile'))
}

// ──────────────────────────────────────────────────────────────────────────────
// 14. orchestrator-deploy-all — Full-stack deploy orchestrator (one button)
// ──────────────────────────────────────────────────────────────────────────────
pipelineJob('orchestrator-deploy-all') {
    description('GitOps orchestrator: builds and deploys supported Adapstory services in dependency order using only Git-tracked pipelines. Foundation → Core services → Dev platform apps → Frontend → Keycloak.')
    parameters {
        // Global
        stringParam('GIT_BRANCH', 'main', 'Branch for all services')
        choiceParam('DEPLOY_ENV', ['dev', 'prod'], 'Target environment')
        choiceParam('BUILD_MODE', ['full', 'promote', 'auto-promote'], 'full = build + deploy | promote = deploy PROMOTE_TAG | auto-promote = read current dev tag and deploy to prod')
        booleanParam('SKIP_TESTS', false, 'Skip tests in downstream jobs (hotfixes only)')
        stringParam('PROMOTE_TAG', '', 'Image tag to promote (required for BUILD_MODE=promote)')
        // Phase 1: Foundation
        booleanParam('BUILD_MASTER_POM', true, 'Phase 1: Deploy master-pom to Nexus')
        booleanParam('BUILD_SHARED_LIBS', true, 'Phase 1: Deploy shared-libs to Nexus')
        // Phase 2: Backend
        booleanParam('BUILD_IDENTITY', true, 'Phase 2: adapstory-identity (BC-16)')
        booleanParam('BUILD_DATA_MODEL_ENGINE', true, 'Phase 2: adapstory-data-model-engine (BC-15)')
        booleanParam('BUILD_MULTI_TENANT_RUNTIME', true, 'Phase 2: adapstory-multi-tenant-runtime (BC-19)')
        booleanParam('BUILD_PLUGIN_LIFECYCLE', true, 'Phase 2: adapstory-plugin-lifecycle (BC-02)')
        booleanParam('BUILD_PLUGIN_GATEWAY', true, 'Phase 2: adapstory-plugin-gateway')
        booleanParam('BUILD_CONTENT_REPOSITORY', true, 'Phase 2: adapstory-content-repository (BC-11)')
        booleanParam('BUILD_PERSONALIZATION_RUNTIME', true, 'Phase 2: adapstory-personalization-runtime (BC-01)')
        booleanParam('BUILD_BFF_SCHOOL', true, 'Phase 2: adapstory-bff-school')
        booleanParam('BUILD_BFF_ADMIN', true, 'Phase 2: adapstory-bff-admin')
        booleanParam('BUILD_BFF_STUDENT', true, 'Phase 2: adapstory-bff-student')
        // Phase 3: Dev-only platform apps
        booleanParam('BUILD_AI_COURSE_GENERATOR', true, 'Phase 3: ai-course-generator (dev only)')
        booleanParam('BUILD_DIFY_PLUGIN', true, 'Phase 3: dify-plugin (dev only)')
        booleanParam('BUILD_EDU_KNOWLEDGE_GRAPH', true, 'Phase 3: edu-knowledge-graph (dev only)')
        // Phase 4: Frontend & Keycloak
        booleanParam('BUILD_FRONTEND_STUDENT', true, 'Phase 4: Frontend student app')
        booleanParam('BUILD_FRONTEND_ADMIN', true, 'Phase 4: Frontend admin app')
        booleanParam('BUILD_FRONTEND_SCHOOL', true, 'Phase 4: Frontend school app')
        booleanParam('BUILD_LANDING', true, 'Phase 4: Landing page')
        booleanParam('BUILD_KEYCLOAK', true, 'Phase 5: Keycloak (custom build)')
    }
    definition(gitopsScm('infra/ci/jenkins/pipelines/orchestrator-deploy-all.jenkinsfile'))
}

// ──────────────────────────────────────────────────────────────────────────────
// 15b. plugin-build-python — Python plugin CI/CD pipeline (triggered by BC-02)
// ──────────────────────────────────────────────────────────────────────────────
pipelineJob('plugin-build-python') {
    description('Build, test, scan, sign, and push Python plugin images. Triggered by BC-02 Plugin Lifecycle via REST API. Results sent back via callback.')
    // Allow up to 10 concurrent plugin builds (NFR14)
    throttleConcurrentBuilds {
        maxTotal(10)
    }
    parameters {
        stringParam('PLUGIN_ID', '', 'Plugin identifier (required)')
        stringParam('SOURCE_REPO', '', 'Git repository URL of the plugin source (required)')
        stringParam('BRANCH', 'main', 'Branch to build from')
        stringParam('BUILD_TAG', '', 'Image tag for the build (required)')
        stringParam('CALLBACK_URL', '', 'BC-02 callback URL for build results (required)')
        stringParam('RESOURCE_LIMITS_JSON', '{}', 'Optional resource limits for the plugin container (JSON)')
    }
    definition(gitopsScm('infra/ci/jenkins/pipelines/plugin-build-python.jenkinsfile'))
}

// ──────────────────────────────────────────────────────────────────────────────
// 15c. plugin-build-java — Java plugin CI/CD pipeline (triggered by BC-02)
// ──────────────────────────────────────────────────────────────────────────────
pipelineJob('plugin-build-java') {
    description('Build, test, scan, sign, and push Java plugin images via Maven + Jib. Triggered by BC-02 Plugin Lifecycle via REST API. Results sent back via callback.')
    // Allow up to 10 concurrent plugin builds (NFR14)
    throttleConcurrentBuilds {
        maxTotal(10)
    }
    parameters {
        stringParam('PLUGIN_ID', '', 'Plugin identifier (required)')
        stringParam('SOURCE_REPO', '', 'Git repository URL of the plugin source (required)')
        stringParam('BRANCH', 'main', 'Branch to build from')
        stringParam('BUILD_TAG', '', 'Image tag for the build (required)')
        stringParam('CALLBACK_URL', '', 'BC-02 callback URL for build results (required)')
        stringParam('RESOURCE_LIMITS_JSON', '{}', 'Optional resource limits for the plugin container (JSON)')
    }
    definition(gitopsScm('infra/ci/jenkins/pipelines/plugin-build-java.jenkinsfile'))
}

// ──────────────────────────────────────────────────────────────────────────────
// 16. db-management — PostgreSQL schema reset + Liquibase migration (dev only)
// ──────────────────────────────────────────────────────────────────────────────
pipelineJob('db-management') {
    description('DESTRUCTIVE: Drop schema + re-run Liquibase migrations for selected BC databases in dev. Requires manual confirmation. Dev environment ONLY.')
    parameters {
        booleanParam('RESET_SCHEMAS', false, 'Drop and recreate schema "main" in selected databases (DESTRUCTIVE!)')
        stringParam('TARGET_BCS', '', 'Comma-separated BC numbers to reset: ALL, bc01, bc02, bc11, bc15, bc16, bc19')
        booleanParam('SYNC_BC_APPS', true, 'After reset, trigger ArgoCD sync for BC apps to re-run Liquibase migrations')
    }
    definition(gitopsScm('infra/ci/jenkins/pipelines/db-management.jenkinsfile'))
}
