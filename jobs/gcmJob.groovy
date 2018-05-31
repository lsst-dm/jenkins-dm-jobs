SETUP_STEP = '''#!/bin/bash
git clone https://github.com/lsst/lsstsw.git
cd lsstsw
./bin/deply
export LSSTSW=pwd
export LSST_PATH=$LSSTSW/stack
. bin/setup.sh
'''

CI_STEP = SETUP_STEP + '''
rebuild base
'''

job('gcmTestJob') {
  steps {
      shell(CI_STEP)
  }
}
