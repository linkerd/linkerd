#!/bin/bash
set -eu
set -o pipefail
shopt -s extglob

SEP="$(tput bold)⣷$(tput sgr0)"
LOGFILE="l5d-h2spec.log"
SPIN='⣾⣽⣻⢿⡿⣟⣯⣷'

spin() {
    local pid=$!
    local i=0
    while [ "$(ps a | awk '{print $1}' | grep $pid)" ]; do
        i=$(( (i+1) %8 ))
        printf "\r$(tput bold)${SPIN:$i:1}$(tput sgr0)"
        sleep .1
    done
}

echo "${SEP} running h2spec against linkerd..."
# if we can't find h2spec, download it
if ! [ -e "h2spec" ] ; then
    # if we don't already have a h2spec executable, wget it from github
    echo "${SEP} h2spec not found..."
    # detect OS for downloading h2spec
    case $OSTYPE in
        darwin*)
          echo "${SEP} detected OS as macOS"
          H2SPEC_FILE="h2spec_darwin_amd64.tar.gz"
          ;;
        linux*)
          echo "${SEP} detected OS as linux"
          H2SPEC_FILE="h2spec_linux_amd64.tar.ghz"
          ;;

    esac
    H2SPEC_URL="https://github.com/summerwind/h2spec/releases/download/v2.1.0/${H2SPEC_FILE}"
    printf "${SEP} downloading h2spec..."
    wget "${H2SPEC_URL}" > /dev/null 2>&1 &
    spin
    tar xf "${H2SPEC_FILE}" > /dev/null 2>&1 &
    spin
    printf "\n"
fi

L5D_PATH=$(find . -name 'linkerd-*-exec')

until [ -e "${L5D_PATH}" ]; do
    echo "${SEP} linkerd executable not found!"
    ./sbt linkerd:compile | sed "s/^/${SEP} sbt ${SEP} /"
    L5D_PATH=$(find . -name 'linkerd-*-exec')
done

echo "${SEP} found linkerd executable: $L5D_PATH"

nghttpd 8080 --no-tls 2>&1 &
NGHTTPD_PID=$!
echo "${SEP} started nghttpd"

 # run linkerd and send output to logfile.
"${L5D_PATH}" -log.level=DEBUG linkerd/examples/h2spec.yaml &> "$LOGFILE" &
L5D_PID=$!
printf "${SEP} starting linkerd..."

# make sure to kill the linkerd and nghttpd processes when terminating the
# script, regardless of how
trap '
    echo ${SEP} killing nghttpd; kill ${NGHTTPD_PID};
    echo ${SEP} killing linkerd; kill ${L5D_PID}
    ' EXIT

# wait until linkerd starts...
i=0
set +e
until $(curl -sfo /dev/null http://localhost:9990/admin/ping); do
    i=$(( (i+1) %8 ))
    printf "\r$(tput bold)${SPIN:$i:1}$(tput sgr0)"
    sleep .1
done
printf "\n"
set -e

# run h2spec against linkerd, printing linkerd's logs if h2spec failed.
set +e
./h2spec -p 4140
H2SPEC_STATUS=$?
set -e
if [ "${H2SPEC_STATUS}" -eq 0 ]; then
    echo "${SEP} h2spec passed!"
else
    echo "${SEP} h2spec failed! linkerd logs:"
    cat "${LOGFILE}"
fi

exit "${H2SPEC_STATUS}"
