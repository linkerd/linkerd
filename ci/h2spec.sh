#!/bin/bash
set -eu
set -o pipefail
shopt -s extglob

SEP="$(tput bold)⣷$(tput sgr0)"
LOGFILE=$(mktemp -t l5d-h2spec.log)
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
# test if we have a h2spec binary - either in the working dir,
# or on the PATH
set +e
WHICH_H2SPEC=$(which h2spec)
if [[ $? -eq 0 ]]; then
    # if which found an h2spec executable, just return that path.
    H2SPEC_EXEC="${WHICH_H2SPEC}"
    set -e
else
    set -e
    # which didn't find a h2spec on the path. check if it exists in
    # the working dir, and if not, dow nload it.
    if [ -e "h2spec" ] ; then
        # h2spec was found in the working dir, use that.
        H2SPEC_EXEC="./h2spec"
    else
        # if we don't already have a h2spec executable, download it.
        echo "${SEP} h2spec not found..."
        # detect OS for downloading h2spec.
        case $OSTYPE in
            darwin*)
              echo "${SEP} detected OS as macOS"
              H2SPEC_TAR="h2spec_darwin_amd64.tar.gz"
              ;;
            linux*)
              echo "${SEP} detected OS as linux"
              H2SPEC_TAR="h2spec_linux_amd64.tar.ghz"
              ;;
        esac
        # cd into /usr/local/bin to install h2spec.
        DIR=$(pwd)
        cd /usr/local/bin
        # download h2spec.
        H2SPEC_URL="https://github.com/summerwind/h2spec/releases/download/v2.1.0/${H2SPEC_TAR}"
        printf "${SEP} downloading h2spec..."
        wget -O "${H2SPEC_TAR}" "${H2SPEC_URL}" > /dev/null 2>&1 &
        spin
        # untar.
        tar xf "${H2SPEC_TAR}" > /dev/null &
        spin
        printf " downloading h2spec...done!\n"
        cd $DIR
        H2SPEC_EXEC="/usr/local/bin/h2spec"
    fi
fi

# test for presence of nghttpd
set +e
command -v nghttpd > /dev/null
NGHTTPD_STATUS=$?
set -e
if [[ NGHTTPD_STATUS -ne 0 ]]; then
    echo "${SEP} nghttpd not found; please install nghttpd before continuing."
    exit "${NGHTTPD_STATUS}"
fi

# find linkerd executable.
L5D_PATH=$(find . -name 'linkerd-*-exec')

if ! [ -e "${L5D_PATH}" ]; then
    # if we couldn't find a linkerd-exec, build it.
    echo "${SEP} linkerd executable not found!"
    printf "${SEP} building linkerd..."
    ./sbt linkerd/assembly > /dev/null 2>&1 &
    spin
    printf " building linkerd...done!\n"
    L5D_PATH=$(find . -name 'linkerd-*-exec')
fi

echo "${SEP} found linkerd executable: $L5D_PATH"

# start nghttpd.
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
printf " starting linkerd...done!\n"

# run h2spec against linkerd, printing linkerd's logs if h2spec failed.
"${H2SPEC_EXEC}" -p 4140
H2SPEC_STATUS=$?
set -e
if [ "${H2SPEC_STATUS}" -eq 0 ]; then
    echo "${SEP} h2spec passed!"
else
    echo "${SEP} h2spec failed! linkerd logs: ${LOGFILE}"
fi

exit "${H2SPEC_STATUS}"
