#!/usr/bin/env python3
"""
Generate minimal ONNX models for GNN integration tests (CI / local).
Output: libs/gnn/src/test/resources/models/test_link_pred.onnx, test_node_class.onnx
"""
import os
import torch
import torch.nn as nn

OUTPUT_DIR = "libs/gnn/src/test/resources/models"


class TinyLinkPred(nn.Module):
    def __init__(self):
        super().__init__()
        self.linear = nn.Linear(32, 16)

    def forward(self, x, edge_index):
        return self.linear(x)


class TinyNodeClass(nn.Module):
    def __init__(self):
        super().__init__()
        self.linear = nn.Linear(32, 7)

    def forward(self, x, edge_index):
        return self.linear(x)


def main():
    os.makedirs(OUTPUT_DIR, exist_ok=True)
    dummy_x = torch.randn(5, 32)
    dummy_ei = torch.tensor([[0, 1, 2], [1, 2, 3]], dtype=torch.long)

    torch.onnx.export(
        TinyLinkPred(),
        (dummy_x, dummy_ei),
        os.path.join(OUTPUT_DIR, "test_link_pred.onnx"),
        input_names=["node_features", "edge_index"],
        dynamic_axes={
            "node_features": {0: "num_nodes"},
            "edge_index": {1: "num_edges"},
        },
    )
    print(f"Wrote {OUTPUT_DIR}/test_link_pred.onnx")

    torch.onnx.export(
        TinyNodeClass(),
        (dummy_x, dummy_ei),
        os.path.join(OUTPUT_DIR, "test_node_class.onnx"),
        input_names=["node_features", "edge_index"],
        dynamic_axes={
            "node_features": {0: "num_nodes"},
            "edge_index": {1: "num_edges"},
        },
    )
    print(f"Wrote {OUTPUT_DIR}/test_node_class.onnx")


if __name__ == "__main__":
    main()
