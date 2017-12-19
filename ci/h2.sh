#!/bin/bash
set -eu
set -o pipefail
shopt -s extglob

SEP="$(tput bold)⣷$(tput sgr0)"
LOGFILE=$(mktemp -t l5d-h2spec.log)
SPIN='⣾⣽⣻⢿⡿⣟⣯⣷'

##### Functions
spin() {
    local pid=$!
    local i=0
    while [ "$(ps a | awk '{print $1}' | grep $pid)" ]; do
        i=$(( (i+1) %8 ))
        printf "\r$(tput bold)${SPIN:$i:1}$(tput sgr0)"
        sleep .1
    done
}

# Search for an existing h2spec executable or download it if
# none was found.
#
# Sets the global variable H2SPEC_PATH to the path to the h2spec executable.
get_h2spec() {
    # test if we have a h2spec binary - either in the working dir,
    # or on the PATH
    set +e
    local which_h2spec
    local which_status
    which_h2spec=$(which_h2spec h2spec)
    which_status=$?
    set -e
    if [[ which_status -eq 0 ]]; then
        # if which_h2spec found an h2spec executable, just return that path.
        H2SPEC_PATH="${which_h2spec}"
    else
        local home_bin 
        home_bin="${HOME}/bin"
        # which_h2spec didn't find a h2spec on the path. check if it exists in
        # ~/bin (which_h2spec may not be on the path) and if not, download it.
        if ! [ -e "${home_bin}/h2spec" ] ; then
            # if we don't already have a h2spec executable, download it.
            echo "${SEP} h2spec not found..."
            # detect OS for downloading h2spec.
            local h2spec_tar # name of the h2spec tarball for this OS
            case $OSTYPE in
                darwin*)
                echo "${SEP} detected OS as macOS"
                h2spec_tar="h2spec_darwin_amd64.tar.gz"
                ;;
                linux*)
                echo "${SEP} detected OS as linux"
                h2spec_tar="h2spec_linux_amd64.tar.ghz"
                ;;
            esac

            local h2spec_url # url of the h2spec tarball
            local h2spec_tmp # temp dir to extract the tarball into
            local tar_path  # path to the tarball in the temp dir
            # download h2spec.
            h2spec_url="https://github.com/summerwind/h2spec/releases/download/v2.1.0/${h2spec_tar}"
            h2spec_tmp=$(mktemp -d -t h2spec)
            tar_path="${h2spec_tmp}/${h2spec_tar}"
            printf "${SEP} downloading h2spec..."
            wget -O "${tar_path}" "${h2spec_url}" > /dev/null 2>&1 &
            spin
            # untar.
            if ! [ -e $home_bin ]; then
                mkdir $home_bin
            fi
            tar xf "${tar_path}" -C "${HOME}/bin" > /dev/null &
            spin
            printf " downloaded h2spec to ~/bin.\n"
        fi
        H2SPEC_PATH="${HOME}/bin/h2spec"
    fi
}

# test for presence of nghttpd
nghttpd_exists() {
    set +e
    command -v nghttpd > /dev/null
    local status=$?
    set -e
    return ${status}
}

# find an existing linkerd executable or compile it if none is found.
#
# sets global variable $L5D_PATH
get_l5d() {
    # formatting arg for `find` varies between macOS/BSD and Linuces.
    # local find_args
    # # case $OSTYPE in
    # #     darwin*|bsd*)
    # #         find_args="-print0 | xargs -0 stat -f \"%m %N\" "
    # #         ;;
    # #     linux*)
    # #         find_arg="find linkerd/target/scala-2.12 -name 'linkerd-*-exec' -type f -printf '%T@ %p\n'"
    # #         ;;
    # esac
    # find the most recent linkerd executable.
    L5D_PATH=$(
        # find all files matching the pattern `linkerd-*-exec` in the 
        # target directory.
        find linkerd/target/scala-2.12 -name 'linkerd-*-exec' -type f -print0 |
        # use ls -tr to order the files by oldest to newest
        xargs -0 ls -tr | 
        # take the last item --- the latest linkerd executable.
        tail -n 1
    )

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
}

# start nghttpd + linkerd.
# 
# sets trap to kill l5d/nghttpd on exit.
launch_l5d() {
    # test that nghttpd exists, or else exit.
    if ! nghttpd_exists; then
        echo "${SEP} nghttpd not found; please install nghttpd before continuing."
        exit $?
    fi

    # get path to linkerd or compile if it doesn't exist.
    get_l5d

    # start nghttpd.
    local nghttpd_pid
    nghttpd 8080 --no-tls 2>&1 &
    nghttpd_pid=$!
    echo "${SEP} started nghttpd"

    # run linkerd and send output to logfile.
    local l5d_pid
    "${L5D_PATH}" -log.level=DEBUG linkerd/examples/h2spec.yaml &> "${LOGFILE}" &
    l5d_pid=$!

    # make sure to kill the linkerd and nghttpd processes when terminating the
    # script, regardless of how
    trap '
        echo ${SEP} killing nghttpd; kill ${nghttpd_pid};
        echo ${SEP} killing linkerd; kill ${l5d_pid}
        ' EXIT

    # wait until linkerd starts...
    printf "${SEP} starting linkerd..."
    local i
    i=0
    set +e
    until $(curl -sfo /dev/null http://localhost:9990/admin/ping); do
        i=$(( (i+1) %8 ))
        printf "\r$(tput bold)${SPIN:$i:1}$(tput sgr0)"
        sleep .1
    done
    printf " started linkerd.\n"
}

# print usage message
usage () {
    echo "Usage: $(basename "$0") {spec|load}... --- run h2spec, h2load, or both."
}

if [ "$#" -eq 0 ]; then
    usage
    exit 64
fi
launch_l5d
while [ "$1" != "" ]; do
    case $1 in
        spec)
            echo "${SEP} running h2spec against linkerd..."
            get_h2spec
            # run h2spec against linkerd, printing linkerd's logs if h2spec failed.
            "${H2SPEC_PATH}" -p 4140
            H2SPEC_STATUS=$?
            set -e
            if [ "${H2SPEC_STATUS}" -eq 0 ]; then
                echo "${SEP} h2spec passed!"
            else
                echo "${SEP} h2spec failed! linkerd logs: ${LOGFILE}"
            fi
            ;;
        load)    
            echo "h2load: not yet implemented"
            ;;
        * )
            usage
            exit 1
            ;;
    esac
    shift
done