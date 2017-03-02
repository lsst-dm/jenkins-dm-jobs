import util.StackWrapper

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
    product: 'lsst_apps',
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
    product: 'lsst_py3',
    skip_demo: true,
    python: 'py3',
  ],
  [
    product: 'sims_utils',
    skip_demo: true,
  ],
  [
    product: 'ci_hsc',
    skip_demo: true,
  ],
  [
    product: 'lsst_obs',
    skip_demo: true,
  ],
].each { j ->
  def stack = new StackWrapper(j)

  stack.build(this)
}
