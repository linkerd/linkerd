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
WHICH_H2SPEC_STATUS=$?
set -e
if [[ WHICH_H2SPEC_STATUS -eq 0 ]]; then
    # if which found an h2spec executable, just return that path.
    H2SPEC_EXEC="${WHICH_H2SPEC}"
else
    HOME_BIN="${HOME}/bin"
    # which didn't find a h2spec on the path. check if it exists in
    # ~/bin (which may not be on the path) and if not, download it.
    if ! [ -e "${HOME_BIN}/h2spec" ] ; then
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

        # download h2spec.
        H2SPEC_URL="https://github.com/summerwind/h2spec/releases/download/v2.1.0/${H2SPEC_TAR}"
        H2SPEC_TMP=$(mktemp -d -t h2spec)
        TAR_PATH="${H2SPEC_TMP}/${H2SPEC_TAR}"
        printf "${SEP} downloading h2spec..."
        wget -O "${TAR_PATH}" "${H2SPEC_URL}" > /dev/null 2>&1 &
        spin
        # untar.
        if ! [ -e $HOME_BIN ]; then
            mkdir $HOME_BIN
        fi
        tar xf "${TAR_PATH}" -C "${HOME}/bin" > /dev/null &
        spin
        printf " downloaded h2spec to ~/bin.\n"
    fi
    H2SPEC_EXEC="${HOME}/bin/h2spec"
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
L5D_PATH=$(find linkerd/target/scala-2.12 -name 'linkerd-*-exec')

if ! [ -e "${L5D_PATH}" ]; then
    # if we couldn't find a linkerd-exec, build it.
    echo "${SEP} linkerd executable not found!"
    printf "${SEP} building linkerd..."
    ./sbt linkerd/assembly > /dev/null 2>&1 &
    spin
    L5D_PATH=$(find linkerd/target/scala-2.12 -name 'linkerd-*-exec')
    printf " built ${L5D_PATH}.\n"
else
    echo "${SEP} found linkerd executable: $L5D_PATH"
fi

# start nghttpd.
nghttpd 8080 --no-tls 2>&1 &
NGHTTPD_PID=$!
echo "${SEP} started nghttpd"

 # run linkerd and send output to logfile.
"${L5D_PATH}" -log.level=DEBUG linkerd/examples/h2spec.yaml &> "${LOGFILE}" &
L5D_PID=$!

# make sure to kill the linkerd and nghttpd processes when terminating the
# script, regardless of how
trap '
    echo ${SEP} killing nghttpd; kill ${NGHTTPD_PID};
    echo ${SEP} killing linkerd; kill ${L5D_PID}
    ' EXIT

# wait until linkerd starts...
printf "${SEP} starting linkerd..."
i=0
set +e
until $(curl -sfo /dev/null http://localhost:9990/admin/ping); do
    i=$(( (i+1) %8 ))
    printf "\r$(tput bold)${SPIN:$i:1}$(tput sgr0)"
    sleep .1
done
printf " started linkerd.\n"

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
