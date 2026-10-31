package fixtures.skid;

/** An enum with a constructor argument and a constant-specific body. */
public enum Kind {
    FOOD("food"),
    RENT("rent"),
    OTHER("other") {
        @Override
        public boolean essential() {
            return false;
        }
    };

    private final String code;

    Kind(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public boolean essential() {
        return true;
    }

    public static Kind fromCode(String code) {
        for (Kind kind : values()) {
            if (kind.code.equals(code)) {
                return kind;
            }
        }
        return OTHER;
    }
}
