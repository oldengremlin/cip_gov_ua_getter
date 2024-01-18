#!/usr/bin/env bash

source /etc/profile.d/maven.sh

export JAVA_PROGRAM_ARGS=`echo "$@"`

##
## Without plugin org.codehaus.mojo
##
#export MAIN_CLASS=net.ukrcom.cip_gov_ua_getter.Cip_gov_ua_getter
#mvn exec:java \
#    -Duptodate.skip=true \
#    -Dexec.mainClass="${MAIN_CLASS}" \
#    -Dexec.args="${JAVA_PROGRAM_ARGS}"

##
## With plugin org.codehaus.mojo
##
#mvn exec:java \
#    -Duptodate.skip=true \
#    -Dexec.args="${JAVA_PROGRAM_ARGS}"

mvn -q exec:java
