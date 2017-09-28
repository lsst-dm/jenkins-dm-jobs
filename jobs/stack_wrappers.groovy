import util.Common
Common.makeFolders(this)

import util.StackOsMatrix

def py2Job = 'science-pipelines/py2-boondocks'

[
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
