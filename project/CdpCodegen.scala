import java.io.File

import scala.collection.mutable

/** Generates Scala sources for every Chrome DevTools Protocol domain from the
  * protocol definition JSON (`browser_protocol.json` / `js_protocol.json`).
  *
  * Output layout: one file per domain, each holding an `object <Domain>` in
  * package `io.github.windymelt.cdpscala.protocol` with
  *
  *   - types (`case class` for objects, `enum` for string enums, type aliases
  *     otherwise) with hand-written circe codecs,
  *   - `<Command>Params` / `<Command>Result` case classes and a method per
  *     command taking `(using CDPSession)`,
  *   - `object Events` with a case class and a `CDPEvent` given per event.
  *
  * Codecs are emitted explicitly instead of derived: derivation of ~2500
  * classes is slow to compile, and explicit encoders let us omit `None` fields
  * entirely (Chrome rejects `null` for some optional parameters).
  */
object CdpCodegen {
  val Package = "io.github.windymelt.cdpscala.protocol"
  private val Root = s"_root_.$Package"
  private val Core = "_root_.io.github.windymelt.cdpscala"
  private val CirceJson = "_root_.io.circe.Json"
  private val CirceJsonObject = "_root_.io.circe.JsonObject"
  private val Encoder = "_root_.io.circe.Encoder"
  private val Decoder = "_root_.io.circe.Decoder"
  private val IO = "_root_.cats.effect.IO"

  def generate(protocolFiles: Seq[File], outDir: File): Seq[File] = {
    val domains = protocolFiles.flatMap { f =>
      ujson.read(sbt.IO.read(f))("domains").arr.map(_.obj)
    }
    sbt.IO.delete(outDir)
    domains.map { d =>
      val name = d("domain").str
      val file = new File(outDir, s"$name.scala")
      sbt.IO.write(file, new DomainGen(d).render())
      file
    }
  }

  // ---------------------------------------------------------------- naming

  private val keywords = Set(
    "abstract",
    "case",
    "catch",
    "class",
    "def",
    "do",
    "else",
    "enum",
    "export",
    "extends",
    "false",
    "final",
    "finally",
    "for",
    "forSome",
    "given",
    "if",
    "implicit",
    "import",
    "lazy",
    "macro",
    "match",
    "new",
    "null",
    "object",
    "override",
    "package",
    "private",
    "protected",
    "return",
    "sealed",
    "super",
    "then",
    "this",
    "throw",
    "trait",
    "true",
    "try",
    "type",
    "val",
    "var",
    "while",
    "with",
    "yield",
    "end",
    "using",
    "inline",
    "opaque",
    "open",
    "transparent",
    "infix",
    "derives"
  )

  /** Escapes a protocol name so it can be used as a Scala identifier. */
  def ident(name: String): String =
    if (keywords(name) || !name.matches("[A-Za-z_][A-Za-z0-9_]*")) s"`$name`"
    else name

  /** Members of `Object` / `Product` a case class field must not shadow. */
  private val reservedMembers = Set(
    "notify",
    "notifyAll",
    "wait",
    "getClass",
    "hashCode",
    "equals",
    "toString",
    "clone",
    "finalize",
    "synchronized",
    "eq",
    "ne",
    "asInstanceOf",
    "isInstanceOf",
    "copy",
    "canEqual",
    "productArity",
    "productElement",
    "productPrefix",
    "productIterator",
    "productElementName",
    "productElementNames"
  )

  /** Scala name of a field / parameter (the JSON key stays unchanged). */
  def fieldIdent(name: String): String =
    if (reservedMembers(name)) s"`${name}_`" else ident(name)

  def pascal(name: String): String =
    if (name.isEmpty) name else s"${name.head.toUpper}${name.tail}"

  /** `clipboard-read` -> `ClipboardRead`, `RenderFrameHostReused_CrossSite` ->
    * `RenderFrameHostReusedCrossSite`, `3d` -> `_3d`.
    */
  def enumCaseName(value: String): String = {
    val joined =
      value.split("[^A-Za-z0-9]+").filter(_.nonEmpty).map(pascal).mkString
    if (joined.isEmpty) s"`$value`"
    else if (joined.head.isDigit) s"_$joined"
    else joined
  }

  def str(s: String): String = {
    val sb = new StringBuilder("\"")
    s.foreach {
      case '"'  => sb.append("\\\"")
      case '\\' => sb.append("\\\\")
      case '\n' => sb.append("\\n")
      case c    => sb.append(c)
    }
    sb.append('"').toString
  }

  /** Scaladoc comment; nested-comment markers are neutralised since Scala
    * comments nest.
    */
  def doc(
      indent: String,
      description: Option[String],
      notes: Seq[String] = Nil
  ): String = {
    val text = (description.toSeq ++ notes).mkString("\n\n")
    if (text.trim.isEmpty) ""
    else {
      val safe = text.replace("*/", "*&#47;").replace("/*", "&#47;*")
      val lines = safe.split("\n").toSeq.map(_.replaceAll("\\s+$", ""))
      val rest =
        lines.tail.map(l => if (l.isEmpty) s"$indent  *" else s"$indent  * $l")
      (s"$indent/** ${lines.head}" +: rest :+ s"$indent  */")
        .mkString("", "\n", "\n")
    }
  }

  private def flags(o: mutable.Map[String, ujson.Value]): Seq[String] =
    Seq(
      if (o.get("experimental").exists(_.bool))
        Some("@note Experimental in the protocol.")
      else None,
      if (o.get("deprecated").exists(_.bool))
        Some("@note Deprecated in the protocol.")
      else None
    ).flatten

  // ------------------------------------------------------------- per domain

  private final case class Field(
      name: String,
      scalaType: String, // element type, without Option
      optional: Boolean,
      description: Option[String]
  ) {
    def declType: String = if (optional) s"Option[$scalaType]" else scalaType
    def scalaName: String = fieldIdent(name)
    def decl: String =
      s"$scalaName: $declType${if (optional) " = None" else ""}"
  }

  private final class DomainGen(d: mutable.Map[String, ujson.Value]) {
    val domain: String = d("domain").str
    private val out = new StringBuilder
    private val enums =
      mutable.LinkedHashMap.empty[String, (Seq[String], Option[String])]
    private val usedNames = mutable.Set.empty[String]

    private def arr(
        key: String,
        o: mutable.Map[String, ujson.Value] = d
    ): Seq[mutable.Map[String, ujson.Value]] =
      o.get(key).map(_.arr.map(_.obj).toSeq).getOrElse(Nil)

    private def claim(name: String): String = {
      require(!usedNames(name), s"$domain: duplicate generated name $name")
      usedNames += name
      name
    }

    /** Registers an inline enum and returns its (unique) type name. */
    private def inlineEnum(
        base: String,
        values: Seq[String],
        description: Option[String]
    ): String = {
      var name = base
      while (usedNames(name)) name = name + "Enum"
      claim(name)
      enums(name) = (values, description)
      s"$Root.$domain.$name"
    }

    private def refType(ref: String): String =
      if (ref.contains(".")) s"$Root.$ref" else s"$Root.$domain.$ref"

    /** Scala type of a property / parameter / array item. */
    private def typeOf(
        p: mutable.Map[String, ujson.Value],
        enumName: => String
    ): String =
      p.get("$ref") match {
        case Some(r) => refType(r.str)
        case None =>
          p("type").str match {
            case "string" if p.contains("enum") =>
              inlineEnum(
                enumName,
                p("enum").arr.map(_.str).toSeq,
                p.get("description").map(_.str)
              )
            case "string"  => "String"
            case "integer" => "Int"
            case "number"  => "Double"
            case "boolean" => "Boolean"
            case "any"     => CirceJson
            case "object"  => CirceJsonObject
            case "array" =>
              s"List[${typeOf(p("items").obj, enumName + "Item")}]"
            case other => sys.error(s"$domain: unknown type $other")
          }
      }

    private def fields(
        props: Seq[mutable.Map[String, ujson.Value]],
        enumPrefix: String
    ): Seq[Field] =
      props.map { p =>
        val name = p("name").str
        Field(
          name,
          typeOf(p, enumPrefix + pascal(name)),
          p.get("optional").exists(_.bool),
          p.get("description").map(_.str)
        )
      }

    // ------------------------------------------------------------ emitters

    private def caseClass(
        indent: String,
        name: String,
        fs: Seq[Field],
        description: Option[String],
        notes: Seq[String],
        encoder: Boolean,
        decoder: Boolean,
        extraCompanion: Seq[String] = Nil
    ): Unit = {
      val paramDocs = fs.flatMap(f =>
        f.description.map(desc =>
          s"@param ${f.name} ${desc.replace("\n", " ")}"
        )
      )
      out.append(doc(indent, description, notes ++ paramDocs))
      if (fs.isEmpty) out.append(s"${indent}final case class $name()\n")
      else {
        out.append(s"${indent}final case class $name(\n")
        out.append(fs.map(f => s"$indent    ${f.decl}").mkString(",\n"))
        out.append(s"\n$indent)\n")
      }
      out.append(s"${indent}object $name {\n")
      val i2 = indent + "  "
      if (encoder) {
        out.append(
          s"${i2}given encoder: $Encoder.AsObject[$name] = $Encoder.AsObject.instance { v =>\n"
        )
        if (fs.isEmpty) out.append(s"$i2  $CirceJsonObject.empty\n")
        else {
          out.append(
            s"$i2  $CirceJsonObject.fromIterable(\n$i2    List[Option[(String, $CirceJson)]](\n"
          )
          out.append(
            fs.map { f =>
              val enc = s"$Encoder[${f.scalaType}]"
              if (f.optional)
                s"$i2      v.${f.scalaName}.map(x => ${str(f.name)} -> $enc.apply(x))"
              else
                s"$i2      Some(${str(f.name)} -> $enc.apply(v.${f.scalaName}))"
            }.mkString(",\n")
          )
          out.append(s"\n$i2    ).flatten\n$i2  )\n")
        }
        out.append(s"$i2}\n")
      }
      if (decoder) {
        out.append(
          s"${i2}given decoder: $Decoder[$name] = $Decoder.instance { c =>\n"
        )
        if (fs.isEmpty) out.append(s"$i2  Right($name())\n")
        else {
          out.append(s"$i2  for {\n")
          fs.zipWithIndex.foreach { case (f, i) =>
            out.append(s"$i2    f$i <- c.get[${f.declType}](${str(f.name)})\n")
          }
          out.append(
            s"$i2  } yield $name(${fs.indices.map(i => s"f$i").mkString(", ")})\n"
          )
        }
        out.append(s"$i2}\n")
      }
      extraCompanion.foreach(l => out.append(s"$i2$l\n"))
      out.append(s"$indent}\n\n")
    }

    private def enumDef(
        indent: String,
        name: String,
        values: Seq[String],
        description: Option[String],
        notes: Seq[String]
    ): Unit = {
      val base = values.map(enumCaseName)
      val dup =
        base.groupBy(identity).collect { case (k, v) if v.size > 1 => k }.toSet
      val cases =
        values.zip(base).map { case (v, b) => (v, if (dup(b)) s"`$v`" else b) }
      var fallback = "Unrecognized"
      while (cases.exists(_._2 == fallback)) fallback = "_" + fallback
      out.append(
        doc(
          indent,
          description,
          notes :+ s"`$fallback` holds values unknown to this protocol version."
        )
      )
      out.append(s"${indent}enum $name(val value: String) {\n")
      cases.foreach { case (v, c) =>
        out.append(s"$indent  case $c extends $name(${str(v)})\n")
      }
      out.append(s"$indent  case $fallback(raw: String) extends $name(raw)\n")
      out.append(s"$indent}\n")
      out.append(s"${indent}object $name {\n")
      out.append(
        s"$indent  val known: List[$name] = List(${cases.map(_._2).mkString(", ")})\n"
      )
      out.append(
        s"$indent  private val byValue: Map[String, $name] = known.map(e => e.value -> e).toMap\n"
      )
      out.append(
        s"$indent  def fromValue(value: String): $name = byValue.getOrElse(value, $fallback(value))\n"
      )
      out.append(
        s"$indent  given encoder: $Encoder[$name] = $Encoder.encodeString.contramap(_.value)\n"
      )
      out.append(
        s"$indent  given decoder: $Decoder[$name] = $Decoder.decodeString.map(fromValue)\n"
      )
      out.append(s"$indent}\n\n")
    }

    // ------------------------------------------------------------- render

    def render(): String = {
      val types = arr("types")
      types.foreach(t => claim(t("id").str))
      val commands = arr("commands")
      commands.foreach { c =>
        val n = pascal(c("name").str)
        if (arr("parameters", c).nonEmpty) claim(n + "Params")
        if (arr("returns", c).nonEmpty) claim(n + "Result")
      }

      out.append(
        "// Generated by project/CdpCodegen.scala from the Chrome DevTools Protocol definition. DO NOT EDIT.\n"
      )
      out.append(s"package $Package\n\n")
      out.append(doc("", d.get("description").map(_.str), flags(d)))
      out.append(s"object $domain {\n")

      // types
      types.foreach { t =>
        val id = t("id").str
        val desc = t.get("description").map(_.str)
        t("type").str match {
          case "string" if t.contains("enum") =>
            enumDef("  ", id, t("enum").arr.map(_.str).toSeq, desc, flags(t))
          case "object" if t.contains("properties") =>
            caseClass(
              "  ",
              id,
              fields(arr("properties", t), id),
              desc,
              flags(t),
              encoder = true,
              decoder = true
            )
          case _ =>
            out.append(doc("  ", desc, flags(t)))
            out.append(s"  type $id = ${typeOf(t, id + "Item")}\n\n")
        }
      }

      // commands
      commands.foreach { c =>
        val name = c("name").str
        val n = pascal(name)
        val method = s"$domain.$name"
        val params = fields(arr("parameters", c), n)
        val returns = fields(arr("returns", c), n)
        if (params.nonEmpty)
          caseClass(
            "  ",
            n + "Params",
            params,
            Some(s"Parameters of `$method`."),
            Nil,
            encoder = true,
            decoder = false
          )
        if (returns.nonEmpty)
          caseClass(
            "  ",
            n + "Result",
            returns,
            Some(s"Result of `$method`."),
            Nil,
            encoder = false,
            decoder = true
          )

        require(
          !params.exists(_.name == "cdpSession"),
          s"$method: parameter named cdpSession"
        )
        val paramDocs = params.flatMap(f =>
          f.description.map(desc =>
            s"@param ${f.name} ${desc.replace("\n", " ")}"
          )
        )
        out.append(
          doc(
            "  ",
            c.get("description").map(_.str),
            flags(c) ++ paramDocs :+ s"CDP method: `$method`"
          )
        )
        val sig = params.map(_.decl).mkString(", ")
        val retType = if (returns.isEmpty) "Unit" else n + "Result"
        val payload =
          if (params.isEmpty) s"$CirceJsonObject.empty"
          else
            s"${n}Params.encoder.encodeObject(${n}Params(${params.map(_.scalaName).mkString(", ")}))"
        val call =
          if (returns.isEmpty) s"cdpSession.callUnit(${str(method)}, $payload)"
          else s"cdpSession.call[$retType](${str(method)}, $payload)"
        out.append(
          s"  def ${ident(name)}($sig)(using cdpSession: $Core.CDPSession): $IO[$retType] =\n    $call\n\n"
        )
      }

      // events
      val events = arr("events")
      if (events.nonEmpty) {
        out.append(
          s"  /** Events of the $domain domain. Subscribe with `session.subscribe[$domain.Events.X]`. */\n"
        )
        out.append("  object Events {\n")
        events.foreach { e =>
          val name = e("name").str
          val n = pascal(name)
          caseClass(
            "    ",
            n,
            fields(arr("parameters", e), n),
            e.get("description").map(_.str),
            flags(e) :+ s"CDP event: `$domain.$name`",
            encoder = false,
            decoder = true,
            extraCompanion = Seq(
              s"given event: $Core.CDPEvent[$n] = $Core.CDPEvent(${str(s"$domain.$name")})"
            )
          )
        }
        out.append("  }\n\n")
      }

      // inline enums collected while generating fields
      enums.foreach { case (name, (values, desc)) =>
        enumDef("  ", name, values, desc, Nil)
      }

      out.append("}\n")
      out.toString
    }
  }
}
