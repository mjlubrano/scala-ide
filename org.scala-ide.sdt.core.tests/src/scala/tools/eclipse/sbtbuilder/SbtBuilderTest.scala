package scala.tools.eclipse
package sbtbuilder

import org.junit.Test
import org.eclipse.core.runtime.NullProgressMonitor
import org.eclipse.core.resources.IncrementalProjectBuilder
import org.eclipse.jdt.core.IJavaModelMarker
import org.eclipse.core.resources.IResource
import org.junit.Assert
import org.eclipse.core.resources.IMarker
import scala.tools.eclipse.testsetup.SDTTestUtils
import org.eclipse.core.resources.IFile
import org.junit.Ignore
import org.junit.Before
import org.mockito.Mockito._
import org.mockito.Matchers.any
import org.eclipse.jdt.core.IProblemRequestor
import org.eclipse.jdt.core.WorkingCopyOwner
import scala.tools.eclipse.javaelements.ScalaSourceFile

object SbtBuilderTest extends testsetup.TestProjectSetup("builder")

class SbtBuilderTest {

  import SbtBuilderTest._

  @Before
  def setupWorkspace {
    // auto-building is on
    val desc = SDTTestUtils.workspace.getDescription
    desc.setAutoBuilding(true)
    SDTTestUtils.workspace.setDescription(desc)
  }

  @Test def testSimpleBuild() {
    println("building " + project)
    project.clean(new NullProgressMonitor())
    project.underlying.build(IncrementalProjectBuilder.FULL_BUILD, new NullProgressMonitor)

    val units = List(compilationUnit("test/ja/JClassA.java"), compilationUnit("test/sc/ClassA.scala"))
    val noErrors = units.forall { unit =>
      val problems = unit.getUnderlyingResource().findMarkers(IJavaModelMarker.JAVA_MODEL_PROBLEM_MARKER, true, IResource.DEPTH_INFINITE)
      println("problems: %s: %s".format(unit, problems.toList))
      problems.isEmpty
    }

    Assert.assertTrue("Build errors found", noErrors)
  }

  def rebuild(prj: ScalaProject): List[IMarker] = {
    println("building " + prj)
    prj.underlying.build(IncrementalProjectBuilder.INCREMENTAL_BUILD, new NullProgressMonitor)

    getProblemMarkers()
  }

  private def getProblemMarkers() = {
    val units = List(compilationUnit("test/ja/JClassA.java"), compilationUnit("test/sc/ClassA.scala"), compilationUnit("test/dependency/FooClient.scala"))
    units.flatMap { unit =>
      val problems = unit.getUnderlyingResource().findMarkers(IJavaModelMarker.JAVA_MODEL_PROBLEM_MARKER, true, IResource.DEPTH_INFINITE)
      println("problems: %s: %s".format(unit, problems.toList))
      problems
    }
  }

  @Test def dependencyTest() {
    object depProject extends testsetup.TestProjectSetup("builder-sub")

    Assert.assertTrue("Autobuilding", SDTTestUtils.workspace.getDescription().isAutoBuilding())

    println("=== Dependency Test === ")
    project.clean(new NullProgressMonitor())

    val problemsDep = rebuild(depProject.project)
    val problemsOrig = rebuild(project)
    Assert.assertTrue("Should succeed compilation", problemsOrig.isEmpty)

    val fooCU = depProject.compilationUnit("subpack/Foo.scala")
    println("IFile: " + fooCU.getResource().getAdapter(classOf[IFile]).asInstanceOf[IFile])
    SDTTestUtils.changeContentOfFile(depProject.project.underlying, fooCU.getResource().getAdapter(classOf[IFile]).asInstanceOf[IFile], changedFooScala)

    val fooClientCU = scalaCompilationUnit("test/dependency/FooClient.scala")

    println("=== Rebuilding workspace === ")
    SDTTestUtils.workspace.build(IncrementalProjectBuilder.INCREMENTAL_BUILD, null)

    val problems = getProblemMarkers()

    val messages = for (p <- problems) yield p.getAttribute(IMarker.MESSAGE)
    println(messages)

    Assert.assertEquals("Build problems", 2, problems.size)
    Assert.assertEquals("Build Problem should be in FooClient.scala", problems(0).getResource(), fooClientCU.getResource())
    Assert.assertEquals("Number of error messages differ", expectedMessages.size, messages.size)
    Assert.assertEquals("Build error messages differ", expectedMessages.toSet, messages.toSet)

    fooClientCU.doWithSourceFile { (sf, comp) =>
      comp.askReload(fooClientCU, fooClientCU.getContents()).get // synchronize with the good compiler
    }
    
    val pcProblems = fooClientCU.asInstanceOf[ScalaSourceFile].getProblems()
    println(pcProblems)
    Assert.assertEquals("Presentation compiler errors.", 2, pcProblems.size)
  }

  lazy val changedFooScala = """
    package subpack

class Foo1
"""

  lazy val expectedMessages = List(
    "Foo is not a member of subpack",
    "not found: type Foo")
}
