import type { ExpressionError } from "../errors";

import { lexify } from "./lexifier";
import {
  ADD,
  BAD_TOKEN,
  BOOLEAN,
  CALL,
  COMMA,
  COMPARISON,
  END_OF_INPUT,
  FIELD,
  GROUP,
  GROUP_CLOSE,
  IDENTIFIER,
  LOGICAL_AND,
  LOGICAL_NOT,
  LOGICAL_OR,
  MULDIV_OP,
  NUMBER,
  STRING,
  SUB,
} from "./syntax";
import { type NodeType, Token } from "./types";

function plain(error: ExpressionError) {
  return {
    message: error.message,
    len: error.len,
    pos: error.pos,
    friendly: error.friendly,
  };
}

function asToken(token: {
  type: NodeType;
  pos: number;
  text: string;
  value?: string;
}) {
  return new Token({
    ...token,
    length: token.text.length,
  });
}

describe("lexify", () => {
  describe("expressions", () => {
    it("lexifies valid expressions", () => {
      const { tokens, errors } = lexify('case([Total] > 200, [T], "Nothing")');

      expect(errors).toHaveLength(0);
      expect(tokens).toEqual(
        [
          { type: CALL, pos: 0, text: "case" },
          { type: GROUP, pos: 4, text: "(" },
          { type: FIELD, pos: 5, text: "[Total]", value: "Total" },
          { type: COMPARISON, pos: 13, text: ">" },
          { type: NUMBER, pos: 15, text: "200" },
          { type: COMMA, pos: 18, text: "," },
          { type: FIELD, pos: 20, text: "[T]", value: "T" },
          { type: COMMA, pos: 23, text: "," },
          { type: STRING, pos: 25, text: '"Nothing"', value: "Nothing" },
          { type: GROUP_CLOSE, pos: 34, text: ")" },
          { type: END_OF_INPUT, pos: 35, text: "\n" },
        ].map(asToken),
      );
    });

    it("should tokenize simple comparisons", () => {
      {
        const expression = "[Total] < 0";
        const { tokens, errors } = lexify(expression);
        expect(errors).toHaveLength(0);
        expect(tokens).toEqual(
          [
            { type: FIELD, pos: 0, text: "[Total]", value: "Total" },
            { type: COMPARISON, pos: 8, text: "<" },
            { type: NUMBER, pos: 10, text: "0" },
            { type: END_OF_INPUT, pos: 11, text: "\n" },
          ].map(asToken),
        );
      }

      {
        const expression = "[Rate] >= 0";
        const { tokens, errors } = lexify(expression);
        expect(errors).toHaveLength(0);
        expect(tokens).toEqual(
          [
            { type: FIELD, pos: 0, text: "[Rate]", value: "Rate" },
            { type: COMPARISON, pos: 7, text: ">=" },
            { type: NUMBER, pos: 10, text: "0" },
            { type: END_OF_INPUT, pos: 11, text: "\n" },
          ].map(asToken),
        );
      }

      {
        const expression = "NOT [Deal]";
        const { tokens, errors } = lexify(expression);
        expect(errors).toHaveLength(0);
        expect(tokens).toEqual(
          [
            { type: LOGICAL_NOT, pos: 0, text: "NOT" },
            { type: FIELD, pos: 4, text: "[Deal]", value: "Deal" },
            { type: END_OF_INPUT, pos: 10, text: "\n" },
          ].map(asToken),
        );
      }

      {
        const expression = "- Min(5, 10)";
        const { tokens, errors } = lexify(expression);
        expect(errors).toHaveLength(0);
        expect(tokens).toEqual(
          [
            { type: SUB, pos: 0, text: "-" },
            { type: CALL, pos: 2, text: "Min" },
            { type: GROUP, pos: 5, text: "(" },
            { type: NUMBER, pos: 6, text: "5" },
            { type: COMMA, pos: 7, text: "," },
            { type: NUMBER, pos: 9, text: "10" },
            { type: GROUP_CLOSE, pos: 11, text: ")" },
            { type: END_OF_INPUT, pos: 12, text: "\n" },
          ].map(asToken),
        );
      }

      {
        const expression = "[X]+[Y]";
        const { tokens, errors } = lexify(expression);
        expect(errors).toHaveLength(0);
        expect(tokens).toEqual(
          [
            { type: FIELD, pos: 0, text: "[X]", value: "X" },
            { type: ADD, pos: 3, text: "+" },
            { type: FIELD, pos: 4, text: "[Y]", value: "Y" },
            { type: END_OF_INPUT, pos: 7, text: "\n" },
          ].map(asToken),
        );
      }

      {
        const expression = "[P]/[Q]";
        const { tokens, errors } = lexify(expression);
        expect(errors).toHaveLength(0);
        expect(tokens).toEqual(
          [
            { type: FIELD, pos: 0, text: "[P]", value: "P" },
            { type: MULDIV_OP, pos: 3, text: "/" },
            { type: FIELD, pos: 4, text: "[Q]", value: "Q" },
            { type: END_OF_INPUT, pos: 7, text: "\n" },
          ].map(asToken),
        );
      }

      {
        const expression = "TODAY()";
        const { tokens, errors } = lexify(expression);
        expect(errors).toHaveLength(0);
        expect(tokens).toEqual(
          [
            { type: CALL, pos: 0, text: "TODAY" },
            { type: GROUP, pos: 5, text: "(" },
            { type: GROUP_CLOSE, pos: 6, text: ")" },
            { type: END_OF_INPUT, pos: 7, text: "\n" },
          ].map(asToken),
        );
      }

      {
        const expression = "AVG([Tax])";
        const { tokens, errors } = lexify(expression);
        expect(errors).toHaveLength(0);
        expect(tokens).toEqual(
          [
            { type: CALL, pos: 0, text: "AVG" },
            { type: GROUP, pos: 3, text: "(" },
            { type: FIELD, pos: 4, text: "[Tax]", value: "Tax" },
            { type: GROUP_CLOSE, pos: 9, text: ")" },
            { type: END_OF_INPUT, pos: 10, text: "\n" },
          ].map(asToken),
        );
      }

      {
        const expression = "COUNTIF([Discount] < 5)";
        const { tokens, errors } = lexify(expression);
        expect(errors).toHaveLength(0);
        expect(tokens).toEqual(
          [
            { type: CALL, pos: 0, text: "COUNTIF" },
            { type: GROUP, pos: 7, text: "(" },
            { type: FIELD, pos: 8, text: "[Discount]", value: "Discount" },
            { type: COMPARISON, pos: 19, text: "<" },
            { type: NUMBER, pos: 21, end: 22, text: "5" },
            { type: GROUP_CLOSE, pos: 22, text: ")" },
            { type: END_OF_INPUT, pos: 23, text: "\n" },
          ].map(asToken),
        );
      }

      return;
    });
  });

  describe("invalid characters", () => {
    it("handles invalid characters", () => {
      const { tokens, errors } = lexify("10°");
      expect(tokens).toEqual(
        [
          { type: NUMBER, pos: 0, text: "10" },
          { type: BAD_TOKEN, pos: 2, text: "°" },
          { type: END_OF_INPUT, pos: 3, text: "\n" },
        ].map(asToken),
      );
      expect(errors.map(plain)).toEqual([
        {
          message: "Invalid character: °",
          friendly: true,
          len: 1,
          pos: 2,
        },
      ]);
    });

    it("should catch a lone decimal point", () => {
      const { tokens, errors } = lexify(".");
      expect(tokens).toEqual(
        [
          { type: BAD_TOKEN, pos: 0, text: "." },
          { type: END_OF_INPUT, pos: 1, text: "\n" },
        ].map(asToken),
      );
      expect(errors.map(plain)).toEqual([
        {
          friendly: true,
          message: "Invalid character: .",
          pos: 0,
          len: 1,
        },
      ]);
    });
  });

  describe("numbers", () => {
    it("tokenizes numbers correctly", () => {
      const cases = [
        "1",
        "1e2",
        "1E2",
        "1e-2",
        "1E-2",
        ".1e2",
        ".1E2",
        ".1e-2",
        ".1E-2",
        "1.2",
        "1.2e3",
        "1.2E3",
        "1.2e-3",
        "1.2E-3",
        "1.2e03",
        "1.2E03",
        "1.2e-03",
        "1.2E-03",
        ".2e3",
        ".2E3",
        ".2e-3",
        ".2E-3",
        ".1",
        ".1e2",
        "1e99999",
        "1E99999",
        ".1e99999",
        ".1E99999",
        "1e-99999",
        "1E-99999",
        ".1e-99999",
        ".1E-99999",
        ".0",
        ".5",
        "9.",
        "42",
        "0",
        "123456789",
        "3.14",
        "2.7182818284590452353602874",
        "6.022E+23",
        "6.626e-34",
        "299.792458e6",
        "9.e0",
      ];

      for (const expression of cases) {
        const { tokens, errors } = lexify(expression);
        expect(errors).toHaveLength(0);
        expect(tokens).toEqual(
          [
            {
              type: NUMBER,
              pos: 0,
              end: expression.length,
              text: expression,
            },
            { type: END_OF_INPUT, pos: expression.length, text: "\n" },
          ].map(asToken),
        );
      }
    });

    it("handles malformed exponents in mathematical notation", () => {
      const cases = [
        "1E",
        "1e",
        "1.2E",
        "1.2e",
        "1E+",
        "1e+",
        "1.2E+",
        "1.2e+",
        "1E-",
        "1e-",
        "1.2E-",
        "1.2e-",
        "1.2e-",
        ".1E",
        ".1e",
        ".1E+",
        ".1e+",
        ".1E-",
        ".1e-",
        ".1E-",
        "2e",
        "3e+",
        "4E-",
        "4E-",
      ];

      for (const expression of cases) {
        const { errors } = lexify(expression);
        expect(errors.map(plain)).toEqual([
          {
            friendly: true,
            message: "Missing exponent",
            pos: 0,
            len: expect.any(Number),
          },
        ]);
      }
    });
  });

  describe("whitespace", () => {
    it("ignores whitespace", () => {
      const cases = [
        0x0009, // tab
        0x000a, // line feed
        0x000b, // vertical tab
        0x000c, // form feed
        0x000d, // carriage return
        0x0020, // space
        0x0085, // next line
        0x00a0, // non-breaking space
        0x1680, // ogham space
        0x2000, // en quad
        0x2001, // em quad
        0x2002, // en space
        0x2003, // em space
        0x2004, // third em space
        0x2005, // fourth em space
        0x2006, // sixth em space
        0x2007, // figure space
        0x2008, // punctuation space
        0x2009, // thin space
        0x200a, // hair space
        0x2028, // line separator
        0x2029, // paragraph separator
        0x202f, // no break narrow space
        0x205f, // four-eighteenths em space
        0x3000, // cjk language space
      ];

      for (const whitespace of cases) {
        const ws = String.fromCharCode(whitespace);
        const { tokens, errors } = lexify(`a${ws}b`);
        expect(errors).toHaveLength(0);
        expect(tokens).toEqual(
          [
            { type: IDENTIFIER, pos: 0, text: "a" },
            { type: IDENTIFIER, pos: 2, text: "b" },
            { type: END_OF_INPUT, pos: 3, text: "\n" },
          ].map(asToken),
        );
      }
    });
  });

  describe("strings", () => {
    it("tokenizes strings", () => {
      const cases = [
        [`"foo"`, "foo"],
        [`'foo'`, "foo"],
        [`"foo bar"`, "foo bar"],
        [`'foo bar'`, "foo bar"],
        [`"foo'"`, "foo'"],
        [`'foo"'`, 'foo"'],
        [`"'foo"`, "'foo"],
        [`'"foo'`, '"foo'],
        [`"'foo'"`, "'foo'"],
        [`'"foo"'`, '"foo"'],
        [`"foo\\""`, 'foo"'],
        [`'foo\\''`, "foo'"],
        [`"foo\\bbar"`, "foo\bbar"],
        [`'foo\\bbar'`, "foo\bbar"],
        [`"foo\\fbar"`, "foo\fbar"],
        [`'foo\\fbar'`, "foo\fbar"],
        [`"foo\\nbar"`, "foo\nbar"],
        [`'foo\\nbar'`, "foo\nbar"],
        [`"foo\\rbar"`, "foo\rbar"],
        [`'foo\\rbar'`, "foo\rbar"],
        [`"foo\\tbar"`, "foo\tbar"],
        [`'foo\\tbar'`, "foo\tbar"],
        [`"foo\\vbar"`, "foo\x0bbar"],
        [`'foo\\vbar'`, "foo\x0bbar"],
        [`"foo\\"bar"`, 'foo"bar'],
        [`'foo\\'bar'`, "foo'bar"],
        ['"\\n"', "\n"],
        ['"\\r\\n"', "\r\n"],
        ['"say \\"Hi\\""', 'say "Hi"'],
        ["'foo\\tbar'", "foo\tbar"],
      ];

      for (const [expression, value] of cases) {
        const { tokens, errors } = lexify(expression);
        expect(errors).toHaveLength(0);
        expect(tokens).toEqual(
          [
            {
              type: STRING,
              pos: 0,
              text: expression,
              value,
            },
            { type: END_OF_INPUT, pos: expression.length, text: "\n" },
          ].map(asToken),
        );
      }
    });

    it("handles invalid escape sequences", () => {
      const cases = [
        ["'\\x22'", "\\x22"],
        ["'\\wat'", "\\wat"],
      ];

      for (const [expression, value] of cases) {
        const { tokens, errors } = lexify(expression);
        expect(errors).toHaveLength(0);
        expect(tokens).toEqual(
          [
            {
              type: STRING,
              pos: 0,
              text: expression,
              value,
            },
            { type: END_OF_INPUT, pos: expression.length, text: "\n" },
          ].map(asToken),
        );
      }
    });

    it("handles unbalanced strings", () => {
      const cases = [`"single`, `'double`, `"foo\\"`, `'foo\\'`];

      for (const expression of cases) {
        const { errors } = lexify(expression);
        expect(errors.map(plain)).toEqual([
          {
            message: "Missing closing quotes",
            friendly: true,
            pos: 0,
            len: expression.length,
          },
        ]);
      }
    });

    it("should continue to tokenize when encountering an unterminated string literal", () => {
      const { tokens, errors } = lexify(`CONCAT(universe') = [answer]`);
      expect(errors.map(plain)).toEqual([
        {
          message: "Missing closing quotes",
          len: 13,
          pos: 15,
          friendly: true,
        },
      ]);
      expect(tokens).toEqual(
        [
          {
            type: CALL,
            pos: 0,
            text: "CONCAT",
          },
          {
            type: GROUP,
            pos: 6,
            text: "(",
          },
          {
            type: IDENTIFIER,
            pos: 7,
            text: "universe",
          },
          {
            type: BAD_TOKEN,
            pos: 15,
            text: "') = [answer]",
          },
          {
            type: STRING,
            pos: 15,
            text: "') = [answer]",
            value: ") = [answer]",
          },
          { type: END_OF_INPUT, pos: 28, text: "\n" },
        ].map(asToken),
      );
    });
  });

  describe("identifiers", () => {
    it("tokenizes identifiers", () => {
      const cases = [
        "foo",
        "foo_bar",
        "foo.bar",
        "notnull", // should handle other operators as prefix
        "trueish",
        "notable",
        "ANDRA",
        "Oracle",
        "Price",
        "Special_Deal",
        "Product.Rating",
        "_Category",
      ];

      for (const expression of cases) {
        const { tokens, errors } = lexify(expression);
        expect(errors).toHaveLength(0);
        expect(tokens).toEqual(
          [
            {
              type: IDENTIFIER,
              pos: 0,
              text: expression,
            },
            { type: END_OF_INPUT, pos: expression.length, text: "\n" },
          ].map(asToken),
        );
      }
    });
  });

  describe("bracket identifiers", () => {
    it("tokenizes bracket identifiers", () => {
      const cases = [
        "[foo]",
        "[foo bar]",
        "[foo bar bar]",
        '[foo " bar]',
        "[foo ' bar]",
        "[foo ` bar]",
        "[foo ° bar]",
        "[foo , bar]",
        "[Deal]",
        "[Review → Rating]",
        "[Product.Vendor]",
      ];

      for (const expression of cases) {
        const { tokens, errors } = lexify(expression);
        expect(errors).toHaveLength(0);
        expect(tokens).toEqual(
          [
            {
              type: FIELD,
              pos: 0,
              text: expression,
              value: expression.slice(1, -1),
            },
            { type: END_OF_INPUT, pos: expression.length, text: "\n" },
          ].map(asToken),
        );
      }
    });

    it("handles brackets that aren't properly closed", () => {
      const { tokens, errors } = lexify("[foo");
      expect(tokens).toEqual(
        [
          {
            type: BAD_TOKEN,
            pos: 0,
            text: "[foo",
          },
          {
            type: FIELD,
            pos: 0,
            text: "[foo",
            value: "foo",
          },
          { type: END_OF_INPUT, pos: 4, text: "\n" },
        ].map(asToken),
      );
      expect(errors.map(plain)).toEqual([
        {
          message: "Missing a closing bracket",
          friendly: true,
          pos: 0,
          len: 4,
        },
      ]);
    });

    it("handles brackets that aren't properly closed (multiple open brackets)", () => {
      const { tokens, errors } = lexify("[T[");
      expect(tokens).toEqual(
        [
          {
            type: BAD_TOKEN,
            pos: 0,
            text: "[T",
          },
          {
            type: FIELD,
            pos: 0,
            text: "[T",
            value: "T",
          },
          {
            type: BAD_TOKEN,
            pos: 2,
            text: "[",
          },
          { type: END_OF_INPUT, pos: 3, text: "\n" },
        ].map(asToken),
      );
      expect(errors.map(plain)).toEqual([
        {
          message: "Missing a closing bracket",
          pos: 0,
          len: 2,
          friendly: true,
        },
        {
          message: "Invalid character: [",
          len: 1,
          pos: 2,
          friendly: true,
        },
      ]);
    });

    it("should allow escaping brackets within bracket identifiers", () => {
      const cases = [
        ["[T\\[]", "T["],
        ["[T\\]]", "T]"],
        ["[T\\[A\\]]", "T[A]"],
      ];
      for (const [expression, value] of cases) {
        const { tokens, errors } = lexify(expression);
        expect(errors).toHaveLength(0);
        expect(tokens).toEqual(
          [
            {
              type: FIELD,
              pos: 0,
              text: expression,
              value,
            },
            { type: END_OF_INPUT, pos: expression.length, text: "\n" },
          ].map(asToken),
        );
      }
    });

    it("should catch a dangling closing bracket", () => {
      const { errors } = lexify("floor(Total]*1.25)");
      expect(errors.map(plain)).toEqual([
        {
          message: "Missing an opening bracket for Total",
          pos: 11,
          len: 1,
          friendly: true,
        },
      ]);
    });

    it("handles brackets that aren't properly opened", () => {
      const { tokens, errors } = lexify("foo]");
      expect(tokens).toEqual(
        [
          {
            type: IDENTIFIER,
            pos: 0,
            text: "foo",
          },
          {
            type: BAD_TOKEN,
            pos: 3,
            text: "]",
          },
          { type: END_OF_INPUT, pos: 4, text: "\n" },
        ].map(asToken),
      );
      expect(errors.map(plain)).toEqual([
        {
          message: "Missing an opening bracket for foo",
          pos: 3,
          len: 1,
          friendly: true,
        },
      ]);
    });

    it("takes operators into account when dealing with incomplete bracket identifier tokens", () => {
      const { tokens, errors } = lexify('case([Total] > 200, [To, "Nothing")');

      expect(tokens).toEqual(
        [
          { type: CALL, pos: 0, text: "case" },
          { type: GROUP, pos: 4, text: "(" },
          { type: FIELD, pos: 5, text: "[Total]", value: "Total" },
          { type: COMPARISON, pos: 13, text: ">" },
          { type: NUMBER, pos: 15, text: "200" },
          { type: COMMA, pos: 18, text: "," },
          { type: BAD_TOKEN, pos: 20, text: "[To" },
          { type: FIELD, pos: 20, text: "[To", value: "To" },
          { type: COMMA, pos: 23, text: "," },
          { type: STRING, pos: 25, text: '"Nothing"', value: "Nothing" },
          { type: GROUP_CLOSE, pos: 34, text: ")" },
          { type: END_OF_INPUT, pos: 35, text: "\n" },
        ].map(asToken),
      );
      expect(errors.map(plain)).toEqual([
        {
          message: "Missing a closing bracket",
          pos: 20,
          len: 3,
          friendly: true,
        },
      ]);
    });

    it("tokenizes empty identifier brackets", () => {
      const { tokens, errors } = lexify("[]");
      expect(errors).toHaveLength(0);
      expect(tokens).toEqual(
        [
          {
            type: FIELD,
            pos: 0,
            text: "[]",
            value: "",
          },
          { type: END_OF_INPUT, pos: 2, text: "\n" },
        ].map(asToken),
      );
    });

    it("tokenizes consecutive bracket identifiers", () => {
      const { tokens, errors } = lexify("[Foo] [Bar]");
      expect(errors).toHaveLength(0);
      expect(tokens).toEqual(
        [
          {
            type: FIELD,
            pos: 0,
            text: "[Foo]",
            value: "Foo",
          },
          {
            type: FIELD,
            pos: 6,
            text: "[Bar]",
            value: "Bar",
          },
          { type: END_OF_INPUT, pos: 11, text: "\n" },
        ].map(asToken),
      );
    });

    it("tokenizes incomplete bracket identifier followed by whitespace (metabase#50925)", () => {
      const { tokens, errors } = lexify("[Pr [Price]");
      expect(tokens).toEqual(
        [
          {
            type: BAD_TOKEN,
            pos: 0,
            text: "[Pr ",
          },
          {
            type: FIELD,
            pos: 0,
            text: "[Pr ",
            value: "Pr ",
          },
          {
            type: FIELD,
            pos: 4,
            text: "[Price]",
            value: "Price",
          },
          { type: END_OF_INPUT, pos: 11, text: "\n" },
        ].map(asToken),
      );
      expect(errors.map(plain)).toEqual([
        {
          message: "Missing a closing bracket",
          pos: 0,
          len: expect.any(Number),
          friendly: true,
        },
      ]);
      expect(errors[0].len).toBeGreaterThanOrEqual(3);
      expect(errors[0].len).toBeLessThanOrEqual(4);
    });

    it("tokenizes incomplete bracket identifier followed by bracket identifier (metabase#50925)", () => {
      const { tokens, errors } = lexify("[Pr[Price]");
      expect(tokens).toEqual(
        [
          {
            type: BAD_TOKEN,
            pos: 0,
            text: "[Pr",
          },
          {
            type: FIELD,
            pos: 0,
            text: "[Pr",
            value: "Pr",
          },
          {
            type: FIELD,
            pos: 3,
            text: "[Price]",
            value: "Price",
          },
          { type: END_OF_INPUT, pos: 10, text: "\n" },
        ].map(asToken),
      );
      expect(errors.map(plain)).toEqual([
        {
          message: "Missing a closing bracket",
          pos: 0,
          len: expect.any(Number),
          friendly: true,
        },
      ]);
    });
  });

  describe("operators", () => {
    it("tokenizes boolean operators", () => {
      const cases: [string, NodeType, number][] = [
        ["A or B", LOGICAL_OR, 1],
        ["A and B", LOGICAL_AND, 1],
        ["not A", LOGICAL_NOT, 0],
      ];

      const permutations = cases.flatMap(
        ([expression, token, index]: [string, NodeType, number]) =>
          casePermutations(expression).map(
            (str): [string, NodeType, number] => [str, token, index],
          ),
      );

      for (const [expression, op, index] of permutations) {
        const { tokens, errors } = lexify(expression);
        expect(errors).toHaveLength(0);
        expect(tokens[index]).toEqual({
          type: op,
          pos: expect.any(Number),
          end: expect.any(Number),
          text: expect.any(String),
          length: expect.any(Number),
        });
      }
    });
  });

  describe("booleans", () => {
    it("tokenizes booleans correctly", () => {
      const cases = ["true", "false"].flatMap(casePermutations);

      for (const expression of cases) {
        const { tokens, errors } = lexify(expression);
        expect(errors).toHaveLength(0);
        expect(tokens).toEqual(
          [
            {
              type: BOOLEAN,
              pos: 0,
              text: expression,
            },
            { type: END_OF_INPUT, pos: expression.length, text: "\n" },
          ].map(asToken),
        );
      }
    });
  });

  describe("garbage", () => {
    const types = (expr: string) => lexify(expr).tokens.map((t) => t.type);
    const errors = (expr: string) => lexify(expr).errors;

    it("should ignore garbage", () => {
      expect(types("!@^ [Deal]")).toEqual([FIELD, END_OF_INPUT]);
      expect(errors("!")[0].message).toEqual("Invalid character: !");
      expect(errors(" % @")[1].message).toEqual("Invalid character: @");
      expect(errors("    #")[0].pos).toEqual(4);
      expect(errors("    #")[0].len).toEqual(1);
    });
  });
});

/**
 * Takes a string and returns a list of all possible cases of the string.
 *
 * @example
 *   casePermutations("AB") // ["AB", "aB", "Ab", "ab"]
 */
function casePermutations(str: string): string[] {
  let results = [""];

  for (const char of str) {
    const newResults = [];
    for (const perm of results) {
      newResults.push(perm + char.toLowerCase());
      newResults.push(perm + char.toUpperCase());
    }
    results = newResults;
  }

  return results;
}
