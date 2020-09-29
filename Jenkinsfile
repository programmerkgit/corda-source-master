import static com.r3.build.BuildControl.killAllExistingBuildsForJob
@Library('corda-shared-build-pipeline-steps')
import static com.r3.build.BuildControl.killAllExistingBuildsForJob

killAllExistingBuildsForJob(env.JOB_NAME, env.BUILD_NUMBER.toInteger())

pipeline {
    agent { label 'k8s' }
    options {
        timestamps()
        timeout(time: 3, unit: 'HOURS')
    }

    environment {
        DOCKER_TAG_TO_USE = "${env.GIT_COMMIT.subSequence(0, 8)}"
        EXECUTOR_NUMBER = "${env.EXECUTOR_NUMBER}"
        BUILD_ID = "${env.BUILD_ID}-${env.JOB_NAME}"
        ARTIFACTORY_CREDENTIALS = credentials('artifactory-credentials')
    }

    stages {
        stage('Corda Pull Request - Generate Build Image') {
            steps {
                withCredentials([string(credentialsId: 'container_reg_passwd', variable: 'DOCKER_PUSH_PWD')]) {
                    sh "./gradlew --no-daemon " +
                            "-Dkubenetize=true " +
                            "-Ddocker.push.password=\"\${DOCKER_PUSH_PWD}\" " +
                            "-Ddocker.work.dir=\"/tmp/\${EXECUTOR_NUMBER}\" " +
                            "-Ddocker.build.tag=\"\${DOCKER_TAG_TO_USE}\"" +
                            " clean preAllocateForAllParallelUnitTest preAllocateForAllParallelIntegrationTest pushBuildImage --stacktrace"
                }
                sh "kubectl auth can-i get pods"
            }
        }

        stage('Corda Pull Request - Run Tests') {
            parallel {
                stage('Integration Tests') {
                    steps {
                        sh "./gradlew --no-daemon " +
                                "-DbuildId=\"\${BUILD_ID}\" " +
                                "-Dkubenetize=true " +
                                "-Ddocker.run.tag=\"\${DOCKER_TAG_TO_USE}\" " +
                                "-Dartifactory.username=\"\${ARTIFACTORY_CREDENTIALS_USR}\" " +
                                "-Dartifactory.password=\"\${ARTIFACTORY_CREDENTIALS_PSW}\" " +
                                "-Dgit.branch=\"\${GIT_BRANCH}\" " +
                                "-Dgit.target.branch=\"\${CHANGE_TARGET}\" " +
                                "-Ddependx.branch.origin=${env.GIT_COMMIT} " +
                                "-Ddependx.branch.target=${CHANGE_TARGET} " +
                                " allParallelIntegrationTest  --stacktrace"
                    }
                }
                stage('Unit Tests') {
                    steps {
                        sh "./gradlew --no-daemon " +
                                "-DbuildId=\"\${BUILD_ID}\" " +
                                "-Dkubenetize=true " +
                                "-Ddocker.run.tag=\"\${DOCKER_TAG_TO_USE}\" " +
                                "-Dartifactory.username=\"\${ARTIFACTORY_CREDENTIALS_USR}\" " +
                                "-Dartifactory.password=\"\${ARTIFACTORY_CREDENTIALS_PSW}\" " +
                                "-Dgit.branch=\"\${GIT_BRANCH}\" " +
                                "-Dgit.target.branch=\"\${CHANGE_TARGET}\" " +
                                "-Ddependx.branch.origin=${env.GIT_COMMIT} " +
                                "-Ddependx.branch.target=${CHANGE_TARGET} " +
                                " allParallelUnitTest --stacktrace"
                    }
                }
            }
        }
    }

    post {
        always {
            archiveArtifacts artifacts: '**/pod-logs/**/*.log', fingerprint: false
            junit testResults: '**/build/test-results-xml/**/*.xml', keepLongStdio: true
        }
        cleanup {
            deleteDir() /* clean up our workspace */
        }
    }
}
