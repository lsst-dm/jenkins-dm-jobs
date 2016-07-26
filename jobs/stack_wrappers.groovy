import util.StackOsMatrix

[
  [
    product: 'dax_webserv',
    skip_demo: true,
  ],
  [
    product: 'lsst_distrib',
    skip_demo: false,
  ],
  [
    product: 'lsst_sims',
    skip_demo: true,
  ],
  [
    product: 'qserv_distrib',
    skip_demo: true,
  ],
  [
    product: 'sims_utils',
    skip_demo: true,
    cron: 'H * * * *',
  ],
].each { j ->
  def stack = new StackOsMatrix(
    product: j['product'],
    skip_demo: j['skip_demo']
  )

  if (j.containsKey('cron')) {
    stack.cron = j['cron']
  }

  stack.build(this)
}
