import util.Common
Common.makeFolders(this)

import util.CleanBuild

[
  [
    name: 'dax/dax_webserv',
    product: 'dax_webserv',
    skipDemo: true,
    skipDocs: true,
    buildConfig: 'dax-lsstsw-matrix',
    seedJob: SEED_JOB,
  ],
  [
    name: 'dax/qserv_distrib',
    product: 'qserv_distrib',
    skipDemo: true,
    skipDocs: true,
    buildConfig: 'dax-lsstsw-matrix',
    seedJob: SEED_JOB,
  ],
].each { j ->
  def clean = new CleanBuild(j)

  clean.build(this)
}
