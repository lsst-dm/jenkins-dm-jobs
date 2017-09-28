import util.Common
Common.makeFolders(this)

import util.CleanBuild

[
  [
    name: 'science-pipelines/lsst_distrib',
    product: 'lsst_distrib',
    skipDemo: false,
    skipDocs: false,
    seedJob: SEED_JOB,
  ],
  [
    name: 'qserv/dax_webserv',
    product: 'dax_webserv',
    skipDemo: true,
    skipDocs: true,
    seedJob: SEED_JOB,
  ],
  [
    name: 'qserv/qserv_distrib',
    product: 'qserv_distrib',
    skipDemo: true,
    skipDocs: true,
    seedJob: SEED_JOB,
  ],
].each { j ->
  def clean = new CleanBuild(j)

  clean.build(this)
}
