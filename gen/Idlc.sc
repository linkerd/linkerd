import ammonite.ops._

@main
def main(idl: Path) = {
        val name = idl.name.dropRight(idl.ext.size+1)
	println(read(idl).lines.mkString(s"case class $name(", ", ", ")"))
}
