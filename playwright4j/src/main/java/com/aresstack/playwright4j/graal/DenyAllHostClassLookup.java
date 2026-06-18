package com.aresstack.playwright4j.graal;

import java.util.function.Predicate;

final class DenyAllHostClassLookup implements Predicate<String> {

    @Override
    public boolean test(String className) {
        return false;
    }
}
