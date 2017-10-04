import util.Common
Common.makeFolders(this)

import util.StackOsMatrix

def py2Job = 'science-pipelines/py2-boondocks'

[
].each { j ->
  def stack = new StackOsMatrix(j)

  stack.build(this)
}
