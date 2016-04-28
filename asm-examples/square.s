    .data
newline:    .asciiz "\n"

    .text

square:
    mul $v0, $a0, $a0
    j $ra

exit:
    li $v0, 10
    syscall

print_int:
    li $v0, 1
    syscall
    j $ra

print_newline:
    li $v0, 4
    la $a0, newline
    syscall
    j $ra

main:
    li $a0, 4
    jal square

    move $a0, $v0
    jal print_int

    jal print_newline
    j exit
