pipeline {
    agent { label 'infrastructure-local' }
    options { timeout(time: 25, unit: 'MINUTES'); disableConcurrentBuilds(); skipDefaultCheckout(true) }
    parameters {
        string(name: 'SOURCE_REPOSITORY', defaultValue: '', description: 'Trusted local Git repository, not a working source directory')
        string(name: 'SOURCE_REVISION', defaultValue: '', description: 'Exact full 40-character commit SHA')
    }
    environment {
        CI_ENGINE = 'jenkins'
        CI_BUILD_ID = "${BUILD_NUMBER}"
        CI_COMMIT_SHA = "${params.SOURCE_REVISION}"
    }
    stages {
        stage('Checkout pinned commit') {
            steps {
                script {
                    if (!(params.SOURCE_REVISION ==~ /[0-9a-f]{40}/)) { error('Exact commit SHA is required') }
                    if (!params.SOURCE_REPOSITORY?.startsWith('/')) { error('A trusted absolute local Git repository is required') }
                    dir("runs/${env.BUILD_NUMBER}") {
                        sh '''#!/usr/bin/env bash
set -euo pipefail
test ! -e source
git -c core.hooksPath=/dev/null clone --no-local --no-checkout -- "$SOURCE_REPOSITORY" source
cd source
git -c core.hooksPath=/dev/null -c advice.detachedHead=false checkout --detach "$SOURCE_REVISION"
test "$(git rev-parse HEAD)" = "$SOURCE_REVISION"
python3 scripts/ci_pipeline.py prepare
'''
                    }
                }
            }
        }
        stage('Quality and alert rules') {
            steps { dir("runs/${env.BUILD_NUMBER}/source") { sh 'python3 scripts/ci_pipeline.py quality' } }
        }
        stage('Backend and PostgreSQL tests') {
            steps { dir("runs/${env.BUILD_NUMBER}/source") { sh 'python3 scripts/ci_pipeline.py backend' } }
        }
        stage('Frontend tests and build') {
            steps { dir("runs/${env.BUILD_NUMBER}/source") { sh 'python3 scripts/ci_pipeline.py frontend' } }
        }
        stage('Versioned images') {
            steps { dir("runs/${env.BUILD_NUMBER}/source") { sh 'python3 scripts/ci_pipeline.py images' } }
        }
        stage('Browser and runtime provenance') {
            steps { dir("runs/${env.BUILD_NUMBER}/source") { sh 'python3 scripts/ci_pipeline.py browser' } }
        }
        stage('Failure and recovery') {
            steps { dir("runs/${env.BUILD_NUMBER}/source") { sh 'python3 scripts/ci_pipeline.py recovery' } }
        }
        stage('Keycloak authentication') {
            steps { dir("runs/${env.BUILD_NUMBER}/source") { sh 'python3 scripts/ci_pipeline.py authentication' } }
        }
        stage('Verify source and image identity') {
            steps { dir("runs/${env.BUILD_NUMBER}/source") { sh 'python3 scripts/ci_pipeline.py verify' } }
        }
    }
    post {
        always {
            script {
                dir("runs/${env.BUILD_NUMBER}/source") {
                    try {
                        if (fileExists('scripts/ci_pipeline.py')) { sh 'python3 scripts/ci_pipeline.py cleanup' }
                    } finally {
                        junit allowEmptyResults: true, testResults: 'backend/build/test-results/test/TEST-*.xml,frontend/.validation/auth-ci/junit.xml'
                        archiveArtifacts allowEmptyArchive: true, artifacts: 'frontend/playwright-report/**,frontend/.validation/auth-ci/*.xml,frontend/.validation/auth-ci/summary.json,frontend/.validation/alerting/summary.json,frontend/.validation/ci/*.json'
                    }
                }
            }
        }
    }
}
