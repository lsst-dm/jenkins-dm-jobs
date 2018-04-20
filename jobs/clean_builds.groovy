import util.Common
Common.makeFolders(this)

import util.CleanBuild

[
  [
    name: 'science-pipelines/lsst_distrib',
    product: 'lsst_distrib ci_hsc',
    skipDemo: false,
    skipDocs: false,
    seedJob: SEED_JOB,
  ],
  [
    name: 'dax/dax_webserv',
    product: 'dax_webserv',
    skipDemo: true,
    skipDocs: true,
    seedJob: SEED_JOB,
  ],
  [
    name: 'dax/qserv_distrib',
    product: 'qserv_distrib',
    skipDemo: true,
    skipDocs: true,
    seedJob: SEED_JOB,
  ],
  [
    name: 'sims/lsst_sims',
    product: 'lsst_sims',
    branch: 'w.2018.01',
    skipDemo: true,
    skipDocs: true,
    seedJob: SEED_JOB,
  ],
].each { j ->
  def clean = new CleanBuild(j)

  clean.build(this)
}
