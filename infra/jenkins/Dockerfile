FROM jenkins/jenkins:lts-jdk21
RUN jenkins-plugin-cli --plugins workflow-aggregator junit
COPY infra/jenkins/bootstrap.groovy /usr/share/jenkins/ref/init.groovy.d/bootstrap.groovy
