import util.Common
Common.makeFolders(this)

import util.StackOsMatrix

def py2Job = 'science-pipelines/py2-boondocks'

[
  [
    product: 'lsst_distrib',
    skip_demo: false,
  ],
  [
    product: 'lsst_apps',
    skip_demo: false,
  ],
  [
    product: 'ci_hsc',
    skip_demo: true,
    triggerJob: py2Job,
  ],
  [
    product: 'ci_ctio0m9',
    skip_demo: true,
    triggerJob: py2Job,
  ],
  [
    product: 'lsst_obs',
    skip_demo: true,
  ],
  [
    name: 'qserv/dax_webserv',
    product: 'dax_webserv',
    skip_demo: true,
  ],
  [
    name: 'qserv/qserv_distrib',
    product: 'qserv_distrib',
    skip_demo: true,
  ],
  [
    name: 'sims/build',
    product: 'lsst_sims',
    branch: '13.0 v13.0',
    skip_demo: true,
    cron: null,
  ],
].each { j ->
  def stack = new StackOsMatrix(j)

  stack.build(this)
}
