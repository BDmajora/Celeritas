package com.bdmajora.impetus.umbra.shaderpack.materialmap;

// Marker for one parsed value token of a `block.<id>` line: either a block match (BlockEntry) or a %tag match
// (TagEntry)
// No methods, because the two forms share nothing but the position they occupy in a parsed line — the material
// mapper instanceof-checks and handles each on its own
public interface Entry {
}
