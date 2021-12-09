#!/bin/bash
set -eu
set -o pipefail
shopt -s extglob

BOLD="$(tput bold)"
UNBOLD="$(tput sgr0)"
ERR="✖"
OK="✔"
SEP="${BOLD}⠶${UNBOLD}"
LOGFILE=$(mktemp -t l5d-h2spec.log)
SPIN='⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏'
LOAD='⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿'
LOAD_REST='⠐⠐⠐⠐⠐⠐⠐⠐⠐⠐⠐⠐⠐⠐⠐⠐⠐⠐|'
H2SPEC_STATUS=0

##### Functions
spin() {
    local pid=$!
    local i=0
    while [ "$(ps a | awk '{print $1}' | grep $pid)" ]; do
        i=$(( (i+1) %10 ))
        printf "${PHASE} \r$(tput bold)${SPIN:$i:1}$(tput sgr0)"
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
    which_h2spec=$(which h2spec)
    which_status=$?
    set -e
    if [[ which_status -eq 0 ]]; then
        # if which found an h2spec executable, just return that path.
        H2SPEC_PATH="${which_h2spec}"
    else
        local home_bin
        home_bin="${HOME}/bin"
        # which didn't find a h2spec on the path. check if it exists in
        # ~/bin (which_h2spec may not be on the path) and if not, download it.
        if ! [ -e "${home_bin}/h2spec" ] ; then
            # if we don't already have a h2spec executable, download it.
            echo "${PHASE} ${SEP} h2spec not found..."
            # detect OS for downloading h2spec.
            local h2spec_tar # name of the h2spec tarball for this OS
            case $OSTYPE in
                darwin*)
                    echo "${PHASE} ${SEP} detected OS as macOS"
                    h2spec_tar="h2spec_darwin_amd64.tar.gz"
                    ;;
                linux*)
                    echo "${PHASE} ${SEP} detected OS as linux"
                    h2spec_tar="h2spec_linux_amd64.tar.ghz"
                    ;;
                *)
                    echo "${PHASE} ${ERR} unsupported OS detected!"
                    exit 1
                    ;;
            esac

            local h2spec_url # url of the h2spec tarball
            local h2spec_tmp # temp dir to extract the tarball into
            local tar_path  # path to the tarball in the temp dir
            # download h2spec.
            h2spec_url="https://github.com/summerwind/h2spec/releases/download/v2.1.0/${h2spec_tar}"
            h2spec_tmp=$(mktemp -d -t h2spec)
            tar_path="${h2spec_tmp}/${h2spec_tar}"
            printf "%s %s downloading h2spec..." "${PHASE}" "${SEP}"
            wget -O "${tar_path}" "${h2spec_url}" > /dev/null 2>&1 &
            spin
            # untar.
            if ! [ -e $home_bin ]; then
                mkdir $home_bin
            fi
            tar xf "${tar_path}" -C "${HOME}/bin" > /dev/null &
            spin
            printf "\r%s %s downloaded h2spec to ~/bin.\n" "${PHASE}" "${OK}"
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
        echo "${PHASE} ${SEP} linkerd executable not found!"
        printf "%s %s building linkerd..." "${PHASE}" "${SEP}"
        ./sbt linkerd/assembly > /dev/null 2>&1 &
        spin
        L5D_PATH=$(find linkerd/target/scala-2.12 -name 'linkerd-*-exec')
        printf "\r%s %s built %s.\n" "${PHASE}" "${OK}" "${L5D_PATH}"
    else
        echo "${PHASE} ${OK} found linkerd executable: $L5D_PATH"
    fi
}

# start nghttpd + linkerd.
#
# sets trap to kill l5d/nghttpd on exit.
setup() {
    PHASE="${BOLD}setup${UNBOLD}"
    # test that nghttpd exists, or else exit.
    if ! nghttpd_exists; then
        echo "{$PHASE} ${ERR} nghttpd not found; please install nghttpd before continuing."
        exit $?
    fi

    # get path to linkerd or compile if it doesn't exist.
    get_l5d
    set +e
    # start nghttpd.
    nghttpd 8080 --no-tls 2>&1 &
    nghttpd_status=$?
    nghttpd_pid=$!
    set -e
    if [ "${nghttpd_status}" != "0" ]; then
        echo "${PHASE} ${ERR} couldn't start nghttpd!"
        exit ${nghttpd_status}
    fi
    echo "${PHASE} ${OK} started nghttpd"

    # run linkerd and send output to logfile.
    "${L5D_PATH}" -log.level=DEBUG linkerd/examples/h2spec.yaml &> "${LOGFILE}" &
    l5d_pid=$!

    # make sure to kill the linkerd and nghttpd processes when terminating the
    # script, regardless of how
    trap '
        echo ${BOLD}teardown ${SEP} killing nghttpd; kill ${nghttpd_pid};
        echo ${BOLD}teardown ${SEP} killing linkerd; kill ${l5d_pid}
        ' EXIT

    # wait until linkerd starts...
    printf "%s %s starting linkerd..." "${PHASE}" "${SEP}"
    local i
    i=0
    set +e
    until $(curl -sfo /dev/null http://localhost:9990/admin/ping); do
        i=$(( (i+1) %10 ))
        printf "\r%s $(tput bold)%s$(tput sgr0)" "${PHASE}" "${SPIN:$i:1}"
        sleep .1
    done
    printf "\r%s %s started linkerd.\n" "${PHASE}" "${OK}"
}

# print usage message
usage () {
    echo "Usage: $(basename "$0") {spec|load}... --- run h2spec, h2load, or both."
}

if [ "$#" -eq 0 ]; then
    usage
    exit 64
fi
setup

set +u
while [ "$1" != "" ]; do
    case $1 in
        spec)
            PHASE="${BOLD}h2spec${UNBOLD}"
            echo "${PHASE} ${SEP} running h2spec against linkerd..."
            get_h2spec
            # run h2spec against linkerd, printing linkerd's logs if h2spec failed.
            set +e
            "${H2SPEC_PATH}" -p 4140
            H2SPEC_STATUS=$?
            set -e
            if [ "${H2SPEC_STATUS}" -eq 0 ]; then
                echo "${PHASE} ${OK} h2spec passed!"
            else
                echo "${PHASE} ${ERR} h2spec failed! linkerd logs: ${LOGFILE}"
            fi
            ;;
        load)
            PHASE="${BOLD}h2load${UNBOLD}"
            printf "%s %s running h2load against linkerd..." "${PHASE}" "${SEP}"
            i=0
            while read -r line; do
                case $line in
                    starting*)
                        printf "\r%s %s running h2load against linkerd %s" "${PHASE}" "${SEP}" "${LOAD:1:$i}" "${LOAD_REST}"
                        ;;
                    Application*|"")
                        ;;
                    spawning*|progress*)
                        i=$(( (i+1) ))
                        printf "\r%s %s running h2load against linkerd $(tput bold)%s$(tput sgr0)%s" "${PHASE}" "${SEP}" "${LOAD:1:$i}" "${LOAD_REST:$i:20}"
                        ;;
                    finished*)
                        printf "\n%s %s %s\n" "${PHASE}" "${SEP}" "${line}"
                        ;;
                    *)
                        echo "${PHASE} ${SEP} ${line}"
                        ;;
                esac
            done < <(
                h2load --requests=100000        \
                       --clients=10             \
                       --threads=10             \
                       http://localhost:4140/   \
                    2>/dev/null
                )
            ;;
        * )
            usage
            exit 64
            ;;
    esac
    shift
done


exit ${H2SPEC_STATUS}
