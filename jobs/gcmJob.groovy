CI_STEP = '''#!/bin/bash
set -ex

source /opt/rh/devtoolset-6/enable

cd lsstsw
./bin/deploy
source bin/setup.sh

rebuild base
END
chmod 755 run.sh

cat > Dockerfile <<END
    FROM    docker.io/lsstsqre/centos:7-stackbase-devtoolset-6

    ARG     D_USER
    ARG     D_UID
    ARG     D_GROUP
    ARG     D_GID
    ARG     D_HOME

    USER    root
    RUN     mkdir -p "\$(dirname \$D_HOME)"
    RUN     groupadd -g \$D_GID \$D_GROUP
    RUN     useradd -d \$D_HOME -g \$D_GROUP -u \$D_UID \$D_USER

    USER    \$D_USER
    WORKDIR \$D_HOME
END
docker build -t "mybase" \
        --build-arg D_USER="$(id -un)" \
        --build-arg D_UID="$(id -u)" \
        --build-arg D_GROUP="$(id -gn)" \
        --build-arg D_GID="$(id -g)" \
        --build-arg D_HOME="$HOME" \
        .

docker run -v$(pwd):$(pwd) -w$(pwd) -u$(id -u):$(id -g) \
  mybase $(pwd)/run.sh
'''

def lsstswGitURL = 'git//github.com/lsst/lsstsw.git'
def lsstswBranch = '*/master'


job('gcmTestJob') {
  scm {
      git {
          remote {
                name('origin')
                url(lsstswGitURL)
                branch(lsstswBranch)
          }
          extensions {
                relativeTargetDirectory('lsstsw')
          }
      }
  }
  steps {
      shell(CI_STEP)
  }
}
